/*
 * 录制期"自动抽帧补弧"的 GL 离屏通道（上游 camera/ArcMendPass.kt 的 C++ 等价物）：
 * - cur：本帧特效链的渲染目标（引擎把 OES 帧按编码口径画进来）；
 * - pending A/B 乒乓：滞留待发的保留帧；
 * - acc A/B 乒乓：被抽帧的整像素选择累积面（fresh = 整帧替换，防复用残留污染）；
 * - 两张 program：TEXTURE_VS+PASS_THROUGH_2D_FS（拷贝/发帧）、TEXTURE_VS+ARC_MERGE_FS（合并）。
 *
 * 本类只执行绘制指令，保留决策在 arc_flow（ArcRepairFlow）。所有原语在调用方保证的
 * current GL 上下文内执行；DrawPending 画进**当前已绑定**的目标（编码面），不自己绑 FBO。
 */
#ifndef WOTA_ARC_MEND_PASS_H
#define WOTA_ARC_MEND_PASS_H

#include <GLES3/gl3.h>

class ArcMendPass {
public:
    ArcMendPass() = default;
    ~ArcMendPass();

    ArcMendPass(const ArcMendPass&) = delete;
    ArcMendPass& operator=(const ArcMendPass&) = delete;

    /** 惰性建资源；尺寸/VAO 模式变化即重建。失败已内部 Release。 */
    bool Ensure(int width, int height, bool useVao);

    /** 差分死区 D（0..32，8bit luma 级）→ uDeadbandLuma = D/255 */
    void SetDeadband(int deadband);

    bool Ready() const;

    /** 指令执行（调用方保证上下文已 current；失败已记录） */
    GLuint CurFbo() const;
    bool Accumulate(bool fresh);
    bool FoldAccIntoPending();
    bool HoldCurAsPending();
    bool MergeCurAsPending(bool useAcc);

    /** 待发帧 → 当前已绑定的编码面（调用方负责 viewport + swap） */
    bool DrawPending();

    /** 全清零（五个 tex/FBO、两枚 program、VBO/VAO） */
    void Release();

private:
    bool CreateTarget(GLuint* tex, GLuint* fbo);
    bool CopyInto(GLuint dstFbo, GLuint src2D);
    bool MergeInto(GLuint aTex, GLuint bTex, GLuint dstFbo);
    bool DrawQuad(GLint aPosition, GLint aTexCoord);
    bool BuildPrograms();
    void UnbindUnits();
    bool DrainGlError(const char* where);

    int width_ = 0;
    int height_ = 0;
    bool useVao_ = false;

    GLuint curTex_ = 0;
    GLuint curFbo_ = 0;
    GLuint pendTex_[2] = {0, 0};
    GLuint pendFbo_[2] = {0, 0};
    int pendIdx_ = 0;
    GLuint accTex_[2] = {0, 0};
    GLuint accFbo_[2] = {0, 0};
    int accIdx_ = 0;

    GLuint blitProgram_ = 0;
    GLuint mergeProgram_ = 0;
    GLint blitAPosition_ = -1;
    GLint blitATexCoord_ = -1;
    GLint blitUTexMatrix_ = -1;
    GLint blitUFrame_ = -1;
    GLint mergeAPosition_ = -1;
    GLint mergeATexCoord_ = -1;
    GLint mergeUTexMatrix_ = -1;
    GLint mergeUA_ = -1;
    GLint mergeUB_ = -1;
    GLint mergeUDeadband_ = -1;

    GLuint vao_ = 0;
    GLuint posVbo_ = 0;
    GLuint uvVbo_ = 0;

    float deadbandLuma_ = 12.0f / 255.0f;
};

#endif // WOTA_ARC_MEND_PASS_H
