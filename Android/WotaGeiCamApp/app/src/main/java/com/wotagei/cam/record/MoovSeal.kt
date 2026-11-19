package com.wotagei.cam.record

import java.io.File
import java.io.RandomAccessFile

/** 先验所有顶层 box 的长度与 moov 是否存在。随后仍须由 extractor 验证视频轨。 */
internal object MoovSeal {
    fun complete(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val complete = mp4TopLevelComplete(length, { offset, count ->
                    raf.seek(offset)
                    ByteArray(count).also { raf.readFully(it) }
                }, requireMoov = true)
                complete && raf.length() == length
            }
        }.getOrDefault(false)
    }
}
