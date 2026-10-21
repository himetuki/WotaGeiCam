/*
 * GL-0 EGL/GLES3 渲染管线（对齐上游 38d712a 的移植前置，非业务代码）：
 * - Init：eglGetDisplay → eglInitialize → 选 ES3 config → eglCreateWindowSurface →
 *   eglCreateContext(3) → eglMakeCurrent → 编译/链接全屏三角形试验着色器；
 * - Render（XComponent OnFrame，vsync 驱动）：glClear → uTime=timestamp/1e9 → glDrawArrays(3) →
 *   eglSwapBuffers；首帧打一条 hilog（尺寸 + swap 结果）；
 * - 本片仅验证 program/uniform/swap 管线；上游 Shaders.kt 与各 Pass 从 GL-1 起逐文件移植。
 * - 登记：本批不建独立渲染线程（OnFrame 内同步绘制，首片验证优先；真实引擎需独立渲染线程）。
 */
#include "gl_render.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <hilog/log.h>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT EGL_OPENGL_ES3_BIT_KHR
#endif

namespace {
EGLDisplay g_display = EGL_NO_DISPLAY;
EGLSurface g_surface = EGL_NO_SURFACE;
EGLContext g_context = EGL_NO_CONTEXT;
GLuint g_program = 0;
GLuint g_vao = 0;
GLint g_uTime = -1;
uint64_t g_width = 0;
uint64_t g_height = 0;
bool g_ready = false;
bool g_firstFrameLogged = false;

const char* kVertexShader =
    "#version 300 es\n"
    "out vec2 vUv;\n"
    "void main() {\n"
    "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
    "    vUv = p;\n"
    "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform float uTime;\n"
    "in vec2 vUv;\n"
    "out vec4 fragColor;\n"
    "void main() {\n"
    "    fragColor = vec4(\n"
    "        0.15 + 0.55 * (0.5 + 0.5 * sin(uTime * 1.3 + vUv.x * 3.0)),\n"
    "        0.25 + 0.35 * vUv.y,\n"
    "        0.65 - 0.45 * vUv.x,\n"
    "        1.0);\n"
    "}\n";

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

bool BuildProgram()
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
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
    g_uTime = glGetUniformLocation(g_program, "uTime");
    // GLES3 下 gl_VertexID 绘制仍需绑定一个 VAO
    glGenVertexArrays(1, &g_vao);
    glBindVertexArray(g_vao);
    return true;
}

void DestroyGlObjects()
{
    if (g_program != 0) {
        glDeleteProgram(g_program);
        g_program = 0;
    }
    if (g_vao != 0) {
        glDeleteVertexArrays(1, &g_vao);
        g_vao = 0;
    }
    g_uTime = -1;
}
} // namespace

bool WotaGlInit(void* window, uint64_t width, uint64_t height)
{
    if (g_ready) {
        WotaGlResize(width, height);
        return true;
    }
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
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    if (eglChooseConfig(g_display, configAttribs, &config, 1, &numConfigs) != EGL_TRUE || numConfigs < 1) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglChooseConfig failed err=%{public}d configs=%{public}d",
            static_cast<int>(eglGetError()), static_cast<int>(numConfigs));
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    const EGLint surfaceAttribs[] = {EGL_NONE};
    // OHOS EGLNativeWindowType 为整数句柄（unsigned long），与 OHNativeWindow* 互转用 reinterpret_cast
    g_surface = eglCreateWindowSurface(g_display, config,
        reinterpret_cast<EGLNativeWindowType>(window), surfaceAttribs);
    if (g_surface == EGL_NO_SURFACE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateWindowSurface failed err=%{public}d", static_cast<int>(eglGetError()));
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g_context = eglCreateContext(g_display, config, EGL_NO_CONTEXT, contextAttribs);
    if (g_context == EGL_NO_CONTEXT) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateContext failed err=%{public}d", static_cast<int>(eglGetError()));
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglMakeCurrent failed err=%{public}d", static_cast<int>(eglGetError()));
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    g_width = width;
    g_height = height;
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "egl ready %{public}ux%{public}u glVersion=%{public}s",
        static_cast<unsigned>(width), static_cast<unsigned>(height),
        reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    if (!BuildProgram()) {
        WotaGlDestroy();
        return false;
    }
    g_ready = true;
    g_firstFrameLogged = false;
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
    if (!g_ready) {
        return;
    }
    glClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(g_program);
    if (g_uTime >= 0) {
        glUniform1f(g_uTime, static_cast<GLfloat>(static_cast<double>(timestamp) / 1e9));
    }
    glBindVertexArray(g_vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    EGLBoolean swapped = eglSwapBuffers(g_display, g_surface);
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
    if (g_ready) {
        DestroyGlObjects();
    }
    eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (g_context != EGL_NO_CONTEXT) {
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
    }
    if (g_surface != EGL_NO_SURFACE) {
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
    }
    eglTerminate(g_display);
    g_display = EGL_NO_DISPLAY;
    g_ready = false;
    g_firstFrameLogged = false;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "gl destroyed");
}

bool WotaGlReady()
{
    return g_ready;
}
