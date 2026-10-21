/*
 * GL-1 EGL/GLES3 相机帧渲染管线（对齐上游 38d712a 的移植）：
 * - 着色器：上游 Shaders.kt 首批基对 TEXTURE_VS + PASS_THROUGH_FS（shaders.h 逐字）；
 * - 几何：4 顶点 triangle-strip VBO（position/texcoord 交错），uTexMatrix 初值单位阵；
 * - 上下文：ES3 优先；context 创建或 program 链编失败时销毁 context/surface 后以
 *   EGL_CONTEXT_CLIENT_VERSION=2 重建重试一次（登记走的哪条路）；
 * - EGL config：RGBA8 失败后去 alpha 再选一次（P3 ③ fallback）；
 * - 相机帧经 OH_NativeImage 落到 GL_TEXTURE_EXTERNAL_OES 纹理（T3 接入）；
 * - 渲染在 XComponent OnFrame（vsync）内同步绘制；独立渲染线程留白（GL-2）。
 * - 旋转/镜像烘 UV（上游 buildTexCoords）留 GL-2：本批 UV 用原始 quad + NativeImage 变换矩阵。
 */
#include "gl_render.h"
#include "shaders.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h> // GL_TEXTURE_EXTERNAL_OES
#include <hilog/log.h>
#include <native_image/native_image.h>

#include <atomic>
#include <cstring>
#include <string>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT EGL_OPENGL_ES3_BIT_KHR
#endif

namespace {
EGLDisplay g_display = EGL_NO_DISPLAY;
EGLSurface g_surface = EGL_NO_SURFACE;
EGLContext g_context = EGL_NO_CONTEXT;
EGLConfig g_eglConfig = nullptr;
GLuint g_program = 0;
GLuint g_vao = 0;
GLuint g_vbo = 0;
GLint g_aPosition = -1;
GLint g_aTexCoord = -1;
GLint g_uTexMatrix = -1;
GLint g_uFrame = -1;
uint64_t g_width = 0;
uint64_t g_height = 0;
int g_clientVersion = 3;
bool g_useVao = false;
bool g_ready = false;
bool g_firstFrameLogged = false;

// NativeImage（SurfaceTexture 等价物）：相机帧 → GL_TEXTURE_EXTERNAL_OES
OH_NativeImage* g_nativeImage = nullptr;
GLuint g_texId = 0;
std::atomic<bool> g_frameAvailable{false};
bool g_hasFrame = false;
float g_texMatrix[16] = {
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f
};
std::string g_inputSurfaceId;
/** NativeImage 失败原因（空 = 正常）；页面按它区分「未就绪」与「创建失败」 */
std::string g_nativeImageError;
/** 当前 EGLSurface 对应的 native window（新 window 与旧 surface 不匹配时整体重建） */
void* g_window = nullptr;

/** NativeImage 帧可用回调：头文件明令回调内不许调其他接口，只置原子标志 */
void OnFrameAvailableCB(void* context)
{
    (void)context;
    g_frameAvailable.store(true);
}

/** 建 external OES 纹理 + NativeImage（须在 EGL 就绪、上下文 current 的线程调用） */
bool CreateNativeImage()
{
    g_nativeImageError.clear();
    glGenTextures(1, &g_texId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    g_nativeImage = OH_NativeImage_Create(g_texId, GL_TEXTURE_EXTERNAL_OES);
    if (g_nativeImage == nullptr) {
        g_nativeImageError = "create-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "OH_NativeImage_Create failed");
        return false;
    }
    OH_OnFrameAvailableListener listener;
    listener.context = nullptr;
    listener.onFrameAvailable = OnFrameAvailableCB;
    if (OH_NativeImage_SetOnFrameAvailableListener(g_nativeImage, listener) != 0) {
        g_nativeImageError = "set-listener-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "SetOnFrameAvailableListener failed");
        return false;
    }
    uint64_t surfaceId = 0;
    if (OH_NativeImage_GetSurfaceId(g_nativeImage, &surfaceId) == 0) {
        g_inputSurfaceId = std::to_string(surfaceId);
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "native image created surfaceId=%{public}s", g_inputSurfaceId.c_str());
    } else {
        g_nativeImageError = "get-surface-id-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "OH_NativeImage_GetSurfaceId failed");
        return false;
    }
    return true;
}

/** 拆 NativeImage：顺序 = 反注册 listener → destroy → 删纹理（均在 context current 时） */
void DestroyNativeImage()
{
    if (g_nativeImage != nullptr) {
        OH_NativeImage_UnsetOnFrameAvailableListener(g_nativeImage);
        OH_NativeImage_Destroy(&g_nativeImage);
        g_nativeImage = nullptr;
    }
    if (g_texId != 0) {
        glDeleteTextures(1, &g_texId);
        g_texId = 0;
    }
    g_hasFrame = false;
    g_frameAvailable.store(false);
    g_inputSurfaceId.clear();
}

// 全屏四边形：position(-1..1) + texcoord(0..1) 交错，triangle strip 顺序 (0,0)(1,0)(0,1)(1,1)
const float kQuadVertices[] = {
    -1.0f, -1.0f, 0.0f, 0.0f,
     1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f,  1.0f, 0.0f, 1.0f,
     1.0f,  1.0f, 1.0f, 1.0f,
};

GLuint CompileShader(GLenum type, const char* source)
{
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[512] = {0};
        glGetShaderInfoLog(shader, sizeof(info), nullptr, info);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "shader compile failed type=%{public}u log=%{public}s", static_cast<unsigned>(type), info);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool BuildPipeline()
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER, WOTA_TEXTURE_VS);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, WOTA_PASS_THROUGH_FS);
    if (vs == 0 || fs == 0) {
        if (vs != 0) {
            glDeleteShader(vs);
        }
        if (fs != 0) {
            glDeleteShader(fs);
        }
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[512] = {0};
        glGetProgramInfoLog(program, sizeof(info), nullptr, info);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "program link failed log=%{public}s", info);
        glDeleteProgram(program);
        return false;
    }
    g_program = program;
    g_aPosition = glGetAttribLocation(g_program, "aPosition");
    g_aTexCoord = glGetAttribLocation(g_program, "aTexCoord");
    g_uTexMatrix = glGetUniformLocation(g_program, "uTexMatrix");
    g_uFrame = glGetUniformLocation(g_program, "uFrame");

    // GLES3 下绘制要求绑定 VAO；ES2 上下文没有 VAO，逐帧绑 VBO + attrib 指针
    g_useVao = g_clientVersion >= 3;
    if (g_useVao) {
        glGenVertexArrays(1, &g_vao);
        glBindVertexArray(g_vao);
    }
    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);
    if (g_aPosition >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(g_aPosition));
        glVertexAttribPointer(static_cast<GLuint>(g_aPosition), 2, GL_FLOAT, GL_FALSE,
            4 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    if (g_aTexCoord >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(g_aTexCoord));
        glVertexAttribPointer(static_cast<GLuint>(g_aTexCoord), 2, GL_FLOAT, GL_FALSE,
            4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
    }
    glUseProgram(g_program);
    if (g_uFrame >= 0) {
        glUniform1i(g_uFrame, 0); // 采样单元 0（T3 起绑 GL_TEXTURE_EXTERNAL_OES）
    }
    return true;
}

void DestroyGlObjects()
{
    if (g_vbo != 0) {
        glDeleteBuffers(1, &g_vbo);
        g_vbo = 0;
    }
    if (g_vao != 0) {
        glDeleteVertexArrays(1, &g_vao);
        g_vao = 0;
    }
    if (g_program != 0) {
        glDeleteProgram(g_program);
        g_program = 0;
    }
    g_aPosition = -1;
    g_aTexCoord = -1;
    g_uTexMatrix = -1;
    g_uFrame = -1;
}

/** 以指定 GLES 客户端版本建 context + surface + program；任一步失败整体回收返回 false */
bool TryInitWithVersion(void* window, int clientVersion)
{
    const EGLint surfaceAttribs[] = {EGL_NONE};
    g_surface = eglCreateWindowSurface(g_display, g_eglConfig,
        reinterpret_cast<EGLNativeWindowType>(window), surfaceAttribs);
    if (g_surface == EGL_NO_SURFACE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateWindowSurface failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        return false;
    }
    // 上下文属性显式成对给出：ES3 优先，失败退 ES2 重试一次（策略照上游）
    const EGLint contextAttribsEs3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLint contextAttribsEs2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    const EGLint* contextAttribs = clientVersion >= 3 ? contextAttribsEs3 : contextAttribsEs2;
    g_context = eglCreateContext(g_display, g_eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (g_context == EGL_NO_CONTEXT) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateContext failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglMakeCurrent failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    g_clientVersion = clientVersion;
    if (!BuildPipeline()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "program build failed on es%{public}d context", clientVersion);
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    return true;
}
} // namespace

bool WotaGlInit(void* window, uint64_t width, uint64_t height)
{
    if (g_ready) {
        // 新 window 与当前 EGLSurface 不匹配（页面重建/窗口切换未先触发 destroyed）：
        // 旧 EGLSurface 不能复用，整体重建（登记：正常路径仍是 destroyed→created）
        if (window != g_window) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "new window (%{public}p) != old (%{public}p), rebuild GL", window, g_window);
            WotaGlDestroy();
        } else {
            WotaGlResize(width, height);
            return true;
        }
    }
    g_window = window;
    g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_display == EGL_NO_DISPLAY || eglInitialize(g_display, nullptr, nullptr) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglInitialize failed err=%{public}d", static_cast<int>(eglGetError()));
        return false;
    }
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    // 去 alpha fallback（P3 ③）：个别设备/模拟器没有 RGBA8 window config，去掉 alpha 再选一次
    const EGLint configAttribsNoAlpha[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    EGLint firstErr = EGL_SUCCESS;
    if (eglChooseConfig(g_display, configAttribs, &g_eglConfig, 1, &numConfigs) != EGL_TRUE ||
        numConfigs < 1) {
        firstErr = eglGetError();
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglChooseConfig (alpha) failed err=%{public}d configs=%{public}d, retry without alpha",
            static_cast<int>(firstErr), static_cast<int>(numConfigs));
        g_eglConfig = nullptr;
        numConfigs = 0;
        if (eglChooseConfig(g_display, configAttribsNoAlpha, &g_eglConfig, 1, &numConfigs) != EGL_TRUE ||
            numConfigs < 1) {
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "eglChooseConfig (no-alpha) failed err=%{public}d configs=%{public}d (first err=%{public}d)",
                static_cast<int>(eglGetError()), static_cast<int>(numConfigs), static_cast<int>(firstErr));
            eglTerminate(g_display);
            g_display = EGL_NO_DISPLAY;
            return false;
        }
    }
    g_width = width;
    g_height = height;
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));

    // 上下文策略照上游：ES3 优先，失败（含链编拒绝 ES1.00/OES 扩展）退 ES2 重试一次
    bool ok = TryInitWithVersion(window, 3);
    if (!ok) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "fallback to ES2 context");
        ok = TryInitWithVersion(window, 2);
    }
    if (!ok) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "GL init failed on es3 and es2");
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "egl ready %{public}ux%{public}u es=%{public}d glVersion=%{public}s",
        static_cast<unsigned>(width), static_cast<unsigned>(height), g_clientVersion,
        reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    g_ready = true;
    g_firstFrameLogged = false;
    // 上下文就绪后才建 NativeImage（顺序：上下文 → image；Destroy 反之）
    if (!CreateNativeImage()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "GL ready but NativeImage unavailable (no camera frames)");
    }
    return true;
}

void WotaGlResize(uint64_t width, uint64_t height)
{
    if (!g_ready) {
        return;
    }
    g_width = width;
    g_height = height;
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "gl resize %{public}ux%{public}u", static_cast<unsigned>(width), static_cast<unsigned>(height));
}

void WotaGlRender(uint64_t timestamp)
{
    (void)timestamp;
    if (!g_ready) {
        return;
    }
    // 帧可用才拉起新帧（回调里只置位）；无帧时仅清屏（不画未完成纹理）
    if (g_nativeImage != nullptr && g_frameAvailable.exchange(false)) {
        if (OH_NativeImage_UpdateSurfaceImage(g_nativeImage) == 0) {
            OH_NativeImage_GetTransformMatrix(g_nativeImage, g_texMatrix);
            g_hasFrame = true;
        } else {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "UpdateSurfaceImage failed");
        }
    }
    glClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!g_hasFrame || g_nativeImage == nullptr) {
        return;
    }
    glUseProgram(g_program);
    if (g_uTexMatrix >= 0) {
        glUniformMatrix4fv(g_uTexMatrix, 1, GL_FALSE, g_texMatrix);
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    if (g_useVao) {
        // ES3 每帧重绑 VAO（防后续 GL 状态改动把绘制落到未绑定/旧 VAO）
        glBindVertexArray(g_vao);
    } else {
        // ES2 上下文无 VAO：逐帧重绑 VBO 与 attrib 指针
        glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
        if (g_aPosition >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(g_aPosition));
            glVertexAttribPointer(static_cast<GLuint>(g_aPosition), 2, GL_FLOAT, GL_FALSE,
                4 * sizeof(float), reinterpret_cast<const void*>(0));
        }
        if (g_aTexCoord >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(g_aTexCoord));
            glVertexAttribPointer(static_cast<GLuint>(g_aTexCoord), 2, GL_FLOAT, GL_FALSE,
                4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
        }
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    EGLBoolean swapped = eglSwapBuffers(g_display, g_surface);
    if (swapped != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglSwapBuffers failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    if (!g_firstFrameLogged) {
        g_firstFrameLogged = true;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "first frame %{public}ux%{public}u swap=%{public}d",
            static_cast<unsigned>(g_width), static_cast<unsigned>(g_height),
            swapped == EGL_TRUE ? 1 : 0);
    }
}

void WotaGlDestroy()
{
    if (g_display == EGL_NO_DISPLAY) {
        g_ready = false;
        return;
    }
    // 拆序与建序相反：先拆 NativeImage（其纹理依赖当前上下文），再删 GL 对象、拆 EGL
    DestroyNativeImage();
    if (g_ready) {
        DestroyGlObjects();
    }
    if (eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "destroy: eglMakeCurrent(release) failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    if (g_context != EGL_NO_CONTEXT) {
        if (eglDestroyContext(g_display, g_context) != EGL_TRUE) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "destroy: eglDestroyContext failed err=%{public}d", static_cast<int>(eglGetError()));
        }
        g_context = EGL_NO_CONTEXT;
    }
    if (g_surface != EGL_NO_SURFACE) {
        if (eglDestroySurface(g_display, g_surface) != EGL_TRUE) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "destroy: eglDestroySurface failed err=%{public}d", static_cast<int>(eglGetError()));
        }
        g_surface = EGL_NO_SURFACE;
    }
    if (eglTerminate(g_display) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "destroy: eglTerminate failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    g_display = EGL_NO_DISPLAY;
    g_ready = false;
    g_firstFrameLogged = false;
    g_nativeImageError.clear();
    g_window = nullptr;
    // 释放本线程 EGL 绑定（surface 反复重建/页面反复进出不留线程级残留）
    eglReleaseThread();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "gl destroyed");
}

bool WotaGlReady()
{
    return g_ready;
}

std::string WotaGlGetInputSurfaceId()
{
    if (!g_ready || g_nativeImage == nullptr) {
        return std::string();
    }
    return g_inputSurfaceId;
}

std::string WotaGlGetNativeImageError()
{
    return g_nativeImageError;
}
