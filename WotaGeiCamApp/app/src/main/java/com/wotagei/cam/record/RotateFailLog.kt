package com.wotagei.cam.record

import java.io.File

/**
 * 换段受阻的**失败账文件**（2026-10-08 真机缺陷排查口径）。
 *
 * 为什么必须有它：这台 ROM（WIKO GAR-AN60 / EMUI）**吞掉应用日志**——所有 `Log.*` 一个字节
 * 都不进 `logcat -b all`，也没有 `hilog`，`dumpsys media.codec` 直接 "Can't find service"。
 * 于是"换段腿到底卡在哪一步、丢了多久"只能靠**落盘账目**分辨：UI 提示给用户看结论，
 * 这一行给排查者看环节（`ROTATE_STALL:<ms>:<环节>`，环节见 [RotateStage]）。
 *
 * 只在真出事时写（[shouldLog]）：正常录制一个字节都不落，也不新增权限（写在成片同目录，
 * 与 `.drops.json` sidecar 同一先例，adb 直接 pull）。文件超过 [MAX_BYTES] 就整份重写，
 * 避免长会话把用户的相册目录撑起来。
 */
internal object RotateFailLog {

    const val FILE_NAME = "rotate-fail.log"

    /** 单份上限：几十行足够覆盖一整晚的复现轮次，超了就从头重写 */
    private const val MAX_BYTES = 16L * 1024L

    /**
     * 这次结果要不要落账（纯函数，全表单测）：
     * - 换段受阻类失败码（[RecordError.ROTATE_STALL] / [RecordError.ENGINE_ERROR]）⇒ 必须落；
     * - 成功但丢过内容（[RecordResult.lostMs] > 0）⇒ 也要落（用户那条"已保存但丢了 N 秒"的来源）；
     * - 其余（正常成功、与换段无关的失败）⇒ 不落，绝不打扰相册目录。
     */
    fun shouldLog(error: String?, lostMs: Long): Boolean =
        isRotationFailure(error) || lostMs > 0L

    fun isRotationFailure(code: String?): Boolean =
        code != null &&
            (
                code.startsWith(RecordError.ROTATE_STALL) ||
                    code.startsWith(RecordError.ENGINE_ERROR) ||
                    // 已被有界重建接住的 muxer 写失败（不判废整场，但同样要留痕）
                    code.startsWith(MUX_RECOVERED_PREFIX)
                )

    /** 追加一行；超上限先整份重写。任何异常都不抛（档案用途，不许影响收尾） */
    fun append(dir: File, line: String): Boolean = runCatching {
        val f = File(dir, FILE_NAME)
        if (f.isFile && f.length() > MAX_BYTES) f.delete()
        f.appendText(line + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)
}
