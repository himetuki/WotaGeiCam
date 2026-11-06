package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 检测器纯函数（NMS/IoU）+ 模型组校验逻辑单测（JVM；LiteRT/AAR 类不进 JVM 测试）。 */
class StanceDetectorTest {

    private fun box(cx: Float, cy: Float, w: Float, h: Float, score: Float) =
        floatArrayOf(cx, cy, w, h, score)

    @Test
    fun iou_identicalAndDisjoint() {
        val a = box(10f, 10f, 10f, 10f, 0.9f)
        assertEquals(1f, StanceDetector.iou(a, a), 1e-6f)
        val far = box(100f, 100f, 10f, 10f, 0.9f)
        assertEquals(0f, StanceDetector.iou(a, far), 1e-6f)
    }

    @Test
    fun iou_halfOverlap() {
        // 10x10 相邻各偏 5 → 交 50 并 150 → 1/3
        val a = box(10f, 10f, 10f, 10f, 0.9f)
        val b = box(15f, 10f, 10f, 10f, 0.8f)
        assertEquals(1f / 3f, StanceDetector.iou(a, b), 1e-5f)
    }

    @Test
    fun nms_suppressesOverlapKeepsFar() {
        val boxes = listOf(
            box(10f, 10f, 10f, 10f, 0.9f),   // 最高分，保留
            box(11f, 10f, 10f, 10f, 0.8f),   // IoU≈0.82 被压
            box(100f, 100f, 10f, 10f, 0.7f), // 远离，保留
        )
        val kept = StanceDetector.nms(boxes, 0.45f)
        assertEquals(2, kept.size)
        assertEquals(0.9f, kept[0][4], 1e-6f)
        assertEquals(0.7f, kept[1][4], 1e-6f)
    }

    @Test
    fun nms_empty() {
        assertTrue(StanceDetector.nms(emptyList(), 0.45f).isEmpty())
    }

    @Test
    fun modelPack_validateDir_fullPipeline() {
        val root = createTempDir()
        try {
            val dir = File(root, "pack")
            dir.mkdirs()
            // 造三件套（tflite 用小占位文件——校验只对 sha256/结构）
            File(dir, "margins.json").writeText(
                """{"bands":{"left":[0.278,0.430],"right":[0.270,0.387],"bottom":[0.000,0.051]}}""")
            File(dir, "head.json").writeText(
                """{"coef":[0,0,0,0,5],"intercept":-3}""")
            File(dir, "yolo11n_int8.tflite").writeText("fake-int8")
            File(dir, "yolo11n_fp32.tflite").writeText("fake-fp32")
            val files = listOf("margins.json", "head.json", "yolo11n_int8.tflite", "yolo11n_fp32.tflite")
            val shas = files.associateWith { StanceModelPack.sha256Of(File(dir, it)) }
            val meta = """{"schema":1,"features":["cx","by","w","h","conf"],
                "files":{${files.map { "\"$it\":\"${shas[it]}\"" }.joinToString(",")}},
                "head_meta":{"trained_at":"x"}}"""
            File(dir, "pack.meta.json").writeText(meta)

            val m = StanceModelPack.validateDir(dir)
            assertTrue("有效包应通过校验", m != null)
            assertEquals(1.0, (m!!["schema"] as Number).toDouble(), 0.0)

            // 篡改一个文件 → 校验失败
            File(dir, "head.json").appendText("x")
            assertNull(StanceModelPack.validateDir(dir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun modelPack_validateDir_rejectsBadStructure() {
        val root = createTempDir()
        try {
            val dir = File(root, "pack")
            dir.mkdirs()
            // schema 不符
            File(dir, "pack.meta.json").writeText("""{"schema":2}""")
            assertNull(StanceModelPack.validateDir(dir))
            // 缺文件
            File(dir, "pack.meta.json").writeText(
                """{"schema":1,"features":["cx","by","w","h","conf"],"files":{}}""")
            assertNull(StanceModelPack.validateDir(dir))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "stance-test-${System.nanoTime()}").apply { mkdirs() }
}
