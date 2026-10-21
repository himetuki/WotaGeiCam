#include "arc_mend_pass.h"

#include "shaders.h"

#include <GLES2/gl2ext.h> // GL_TEXTURE_EXTERNAL_OES
#include <GLES3/gl3.h>
#include <hilog/log.h>

#include <algorithm>
#include <string>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

namespace {
constexpr int kUnitA = 0;
constexpr int kUnitB = 1;
const float kQuadPositions[8] = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
const float kIdentity[16] = {
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f
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
            "arc mend shader compile failed type=%{public}u log=%{public}s",
            static_cast<unsigned>(type), info);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
} // namespace

ArcMendPass::~ArcMendPass()
{
    Release();
}

bool ArcMendPass::Ensure(int width, int height, bool useVao)
{
    if (width <= 0 || height <= 0) {
        return false;
    }
    if (Ready() && width_ == width && height_ == height && useVao_ == useVao) {
        return true;
    }
    // 清掉引擎此前可能残留的 GL 错误：Ensure 末尾的 DrainGlError 只应判本批资源创建
    while (glGetError() != GL_NO_ERROR) {
    }
    Release();
    width_ = width;
    height_ = height;
    useVao_ = useVao;

    if (!CreateTarget(&curTex_, &curFbo_)) {
        Release();
        return false;
    }
    for (int i = 0; i < 2; i++) {
        if (!CreateTarget(&pendTex_[i], &pendFbo_[i]) || !CreateTarget(&accTex_[i], &accFbo_[i])) {
            Release();
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "arc mend targets incomplete");
            return false;
        }
    }
    pendIdx_ = 0;
    accIdx_ = 0;

    glGenBuffers(1, &posVbo_);
    static const float kIdUv[8] = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};
    glGenBuffers(1, &uvVbo_);
    if (posVbo_ == 0 || uvVbo_ == 0) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "arc mend glGenBuffers failed pos=%{public}u uv=%{public}u",
            static_cast<unsigned>(posVbo_), static_cast<unsigned>(uvVbo_));
        Release();
        return false;
    }
    glBindBuffer(GL_ARRAY_BUFFER, posVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadPositions), kQuadPositions, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, uvVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kIdUv), kIdUv, GL_STATIC_DRAW);
    if (useVao_) {
        glGenVertexArrays(1, &vao_);
        if (vao_ == 0) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "arc mend glGenVertexArrays failed");
            Release();
            return false;
        }
        glBindVertexArray(vao_);
        glBindVertexArray(0);
    }

    if (!BuildPrograms()) {
        Release();
        return false;
    }
    // Ensure 末尾的错误必须传播：任何 GL 错误都按建链失败处理（调用方降级 DROP）
    if (!DrainGlError("mendEnsure")) {
        Release();
        return false;
    }
    return true;
}

bool ArcMendPass::BuildPrograms()
{
    blitProgram_ = 0;
    mergeProgram_ = 0;

    GLuint vs = CompileShader(GL_VERTEX_SHADER, WOTA_TEXTURE_VS);
    GLuint fsBlit = CompileShader(GL_FRAGMENT_SHADER, WOTA_PASS_THROUGH_2D_FS);
    GLuint fsMerge = CompileShader(GL_FRAGMENT_SHADER, WOTA_ARC_MERGE_FS);
    if (vs == 0 || fsBlit == 0 || fsMerge == 0) {
        if (vs != 0) {
            glDeleteShader(vs);
        }
        if (fsBlit != 0) {
            glDeleteShader(fsBlit);
        }
        if (fsMerge != 0) {
            glDeleteShader(fsMerge);
        }
        return false;
    }
    auto link = [](GLuint v, GLuint f) -> GLuint {
        GLuint program = glCreateProgram();
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        GLint ok = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        if (ok != GL_TRUE) {
            char info[512] = {0};
            glGetProgramInfoLog(program, sizeof(info), nullptr, info);
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "arc mend program link failed log=%{public}s", info);
            glDeleteProgram(program);
            return 0;
        }
        return program;
    };
    blitProgram_ = link(vs, fsBlit);
    mergeProgram_ = link(vs, fsMerge);
    glDeleteShader(vs);
    glDeleteShader(fsBlit);
    glDeleteShader(fsMerge);
    if (blitProgram_ == 0 || mergeProgram_ == 0) {
        return false;
    }

    blitAPosition_ = glGetAttribLocation(blitProgram_, "aPosition");
    blitATexCoord_ = glGetAttribLocation(blitProgram_, "aTexCoord");
    blitUTexMatrix_ = glGetUniformLocation(blitProgram_, "uTexMatrix");
    blitUFrame_ = glGetUniformLocation(blitProgram_, "uFrame");
    mergeAPosition_ = glGetAttribLocation(mergeProgram_, "aPosition");
    mergeATexCoord_ = glGetAttribLocation(mergeProgram_, "aTexCoord");
    mergeUTexMatrix_ = glGetUniformLocation(mergeProgram_, "uTexMatrix");
    mergeUA_ = glGetUniformLocation(mergeProgram_, "uA");
    mergeUB_ = glGetUniformLocation(mergeProgram_, "uB");
    mergeUDeadband_ = glGetUniformLocation(mergeProgram_, "uDeadbandLuma");
    return true;
}

void ArcMendPass::SetDeadband(int deadband)
{
    const int clamped = std::min(32, std::max(0, deadband));
    deadbandLuma_ = static_cast<float>(clamped) / 255.0f;
}

bool ArcMendPass::Ready() const
{
    return blitProgram_ != 0 && mergeProgram_ != 0 && curFbo_ != 0;
}

GLuint ArcMendPass::CurFbo() const
{
    return curFbo_;
}

bool ArcMendPass::Accumulate(bool fresh)
{
    if (fresh) {
        // 自上一保留帧以来第一枚被抽帧：整帧替换，清掉上一轮残留
        const bool ok = CopyInto(accFbo_[1 - accIdx_], curTex_);
        if (ok) {
            accIdx_ = 1 - accIdx_;
        }
        return ok;
    }
    // a = 已有累积面、b = 本枚被抽帧：acc = select(acc, cur)
    const bool ok = MergeInto(accTex_[accIdx_], curTex_, accFbo_[1 - accIdx_]);
    if (ok) {
        accIdx_ = 1 - accIdx_;
    }
    return ok;
}

bool ArcMendPass::FoldAccIntoPending()
{
    const bool ok = MergeInto(pendTex_[pendIdx_], accTex_[accIdx_], pendFbo_[1 - pendIdx_]);
    if (ok) {
        pendIdx_ = 1 - pendIdx_;
    }
    return ok;
}

bool ArcMendPass::HoldCurAsPending()
{
    const bool ok = CopyInto(pendFbo_[1 - pendIdx_], curTex_);
    if (ok) {
        pendIdx_ = 1 - pendIdx_;
    }
    return ok;
}

bool ArcMendPass::MergeCurAsPending(bool useAcc)
{
    const bool ok = useAcc
        ? MergeInto(curTex_, accTex_[accIdx_], pendFbo_[1 - pendIdx_])
        : CopyInto(pendFbo_[1 - pendIdx_], curTex_);
    if (ok) {
        pendIdx_ = 1 - pendIdx_;
    }
    return ok;
}

bool ArcMendPass::DrawPending()
{
    if (blitProgram_ == 0) {
        return false;
    }
    glUseProgram(blitProgram_);
    glActiveTexture(GL_TEXTURE0 + kUnitA);
    glBindTexture(GL_TEXTURE_2D, pendTex_[pendIdx_]);
    if (blitUFrame_ >= 0) {
        glUniform1i(blitUFrame_, kUnitA);
    }
    if (blitUTexMatrix_ >= 0) {
        glUniformMatrix4fv(blitUTexMatrix_, 1, GL_FALSE, kIdentity);
    }
    const bool ok = DrawQuad(blitAPosition_, blitATexCoord_);
    UnbindUnits();
    return ok && DrainGlError("mendBlit") == 0;
}

bool ArcMendPass::CopyInto(GLuint dstFbo, GLuint src2D)
{
    if (blitProgram_ == 0) {
        return false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
    glViewport(0, 0, width_, height_);
    glUseProgram(blitProgram_);
    glActiveTexture(GL_TEXTURE0 + kUnitA);
    glBindTexture(GL_TEXTURE_2D, src2D);
    if (blitUFrame_ >= 0) {
        glUniform1i(blitUFrame_, kUnitA);
    }
    if (blitUTexMatrix_ >= 0) {
        glUniformMatrix4fv(blitUTexMatrix_, 1, GL_FALSE, kIdentity);
    }
    const bool ok = DrawQuad(blitAPosition_, blitATexCoord_);
    UnbindUnits();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return ok && DrainGlError("mendCopy") == 0;
}

bool ArcMendPass::MergeInto(GLuint aTex, GLuint bTex, GLuint dstFbo)
{
    if (mergeProgram_ == 0) {
        return false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
    glViewport(0, 0, width_, height_);
    glUseProgram(mergeProgram_);
    glActiveTexture(GL_TEXTURE0 + kUnitA);
    glBindTexture(GL_TEXTURE_2D, aTex);
    glActiveTexture(GL_TEXTURE0 + kUnitB);
    glBindTexture(GL_TEXTURE_2D, bTex);
    if (mergeUA_ >= 0) {
        glUniform1i(mergeUA_, kUnitA);
    }
    if (mergeUB_ >= 0) {
        glUniform1i(mergeUB_, kUnitB);
    }
    if (mergeUDeadband_ >= 0) {
        glUniform1f(mergeUDeadband_, deadbandLuma_);
    }
    if (mergeUTexMatrix_ >= 0) {
        glUniformMatrix4fv(mergeUTexMatrix_, 1, GL_FALSE, kIdentity);
    }
    const bool ok = DrawQuad(mergeAPosition_, mergeATexCoord_);
    UnbindUnits();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return ok && DrainGlError("mendMerge") == 0;
}

bool ArcMendPass::DrawQuad(GLint aPosition, GLint aTexCoord)
{
    if (useVao_) {
        if (vao_ == 0) {
            // 不复用当前绑定的屏幕 VAO（会把 attrib 指针写进 g_vao，污染上屏）
            return false;
        }
        glBindVertexArray(vao_);
    }
    if (aPosition >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aPosition));
        glBindBuffer(GL_ARRAY_BUFFER, posVbo_);
        glVertexAttribPointer(static_cast<GLuint>(aPosition), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    if (aTexCoord >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aTexCoord));
        glBindBuffer(GL_ARRAY_BUFFER, uvVbo_);
        glVertexAttribPointer(static_cast<GLuint>(aTexCoord), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    return true;
}

void ArcMendPass::UnbindUnits()
{
    glActiveTexture(GL_TEXTURE0 + kUnitB);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0 + kUnitA);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
}

bool ArcMendPass::CreateTarget(GLuint* tex, GLuint* fbo)
{
    GLuint texIds[1] = {0};
    glGenTextures(1, texIds);
    const GLuint texId = texIds[0];
    if (texId == 0) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "arc mend glGenTextures failed");
        return false;
    }
    glBindTexture(GL_TEXTURE_2D, texId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // unsized GL_RGBA（与上游 ArcMendPass 及 frost 同一写法）：ES2 回退上 sized GL_RGBA8
    // 不被接受，会令 FBO 不完整并让 MEND 必降 DROP（审查 P2）
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width_, height_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);

    GLuint fboIds[1] = {0};
    glGenFramebuffers(1, fboIds);
    const GLuint fboId = fboIds[0];
    if (fboId == 0) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "arc mend glGenFramebuffers failed");
        glDeleteTextures(1, &texId);
        return false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fboId);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texId, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "arc mend FBO incomplete status=0x%{public}x", static_cast<unsigned>(status));
        if (fboId != 0) {
            glDeleteFramebuffers(1, &fboId);
        }
        if (texId != 0) {
            glDeleteTextures(1, &texId);
        }
        return false;
    }
    *tex = texId;
    *fbo = fboId;
    return true;
}

void ArcMendPass::Release()
{
    if (curFbo_ != 0) {
        glDeleteFramebuffers(1, &curFbo_);
    }
    if (curTex_ != 0) {
        glDeleteTextures(1, &curTex_);
    }
    curFbo_ = 0;
    curTex_ = 0;
    for (int i = 0; i < 2; i++) {
        if (pendFbo_[i] != 0) {
            glDeleteFramebuffers(1, &pendFbo_[i]);
        }
        if (pendTex_[i] != 0) {
            glDeleteTextures(1, &pendTex_[i]);
        }
        if (accFbo_[i] != 0) {
            glDeleteFramebuffers(1, &accFbo_[i]);
        }
        if (accTex_[i] != 0) {
            glDeleteTextures(1, &accTex_[i]);
        }
        pendFbo_[i] = 0;
        pendTex_[i] = 0;
        accFbo_[i] = 0;
        accTex_[i] = 0;
    }
    if (blitProgram_ != 0) {
        glDeleteProgram(blitProgram_);
    }
    if (mergeProgram_ != 0) {
        glDeleteProgram(mergeProgram_);
    }
    blitProgram_ = 0;
    mergeProgram_ = 0;
    if (posVbo_ != 0) {
        glDeleteBuffers(1, &posVbo_);
    }
    if (uvVbo_ != 0) {
        glDeleteBuffers(1, &uvVbo_);
    }
    if (vao_ != 0) {
        glDeleteVertexArrays(1, &vao_);
    }
    posVbo_ = 0;
    uvVbo_ = 0;
    vao_ = 0;
    pendIdx_ = 0;
    accIdx_ = 0;
    width_ = 0;
    height_ = 0;
    useVao_ = false;
}

bool ArcMendPass::DrainGlError(const char* where)
{
    int count = 0;
    GLenum error = glGetError();
    while (error != GL_NO_ERROR) {
        count++;
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "%{public}s: glError 0x%{public}x", where, static_cast<unsigned>(error));
        error = glGetError();
    }
    return count == 0;
}
