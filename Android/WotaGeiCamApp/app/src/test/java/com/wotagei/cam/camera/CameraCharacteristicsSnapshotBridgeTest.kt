package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y08 · 站位分析挂槽不许在 Main 同步查相机服务。
 *
 * ## 缺陷
 * [com.wotagei.cam.stance.StanceController.attachAnalysis] 挂槽前要按 YUV 输出尺寸选档，
 * 尺寸来自宿主导入的 `characteristicsProvider`；而 [CameraController.currentCharacteristics]
 * 每次都同步 `CameraManager.getCameraCharacteristics`。开关点击、回页补挂都在 Main 上走到这条，
 * 冷启或相机服务拥塞时入口停顿（是否 ANR 取决于平台缓存命中）。
 *
 * ## 修法
 * 枚举（`openCurrentLens` 的 IO 段）已经读过每颗镜头的 characteristics——把它随
 * [com.wotagei.cam.core.LensSlot] 留下，`currentCharacteristics()` 只读这份快照。
 * attach 路径与相机服务的唯一接触点因此消失；快照缺失时回退默认档，不在调用线程补查。
 *
 * ## 验收
 * 桥测用计数版 CameraManager 桩证明：枚举完成之后，反复走 provider（模拟开关点击/回页补挂）
 * **一次也不**再进 `getCameraCharacteristics`。
 */
class CameraCharacteristicsSnapshotBridgeTest {

    @Test
    fun `枚举完成后反复取当前特性不再查CameraManager`() {
        CameraRecoveryStartFixture(
            javaClass.classLoader,
            boundarySources = mapOf("android.hardware.camera2.CameraManager" to COUNTING_MANAGER)
        ).use { p ->
            p.bind()
            p.start()
            p.drain()
            p.completeOpen(0)
            p.completeSession(0)
            assertEquals("枚举/开镜链路必须真的走完（否则下面的计数是空转）", 1, p.opened())

            val afterEnum = queries(p)
            assertTrue(
                "计数桩必须先被枚举用到——否则「之后没再查」证明不了任何事",
                afterEnum > 0
            )

            val snapshot = p.platform.call(p.controller, "currentCharacteristics")
            assertNotNull(
                "枚举完成且已有当前槽位时必须能拿到 characteristics 快照（不再现查）",
                snapshot
            )
            val slot = p.state("CurrentSlot")
            assertNotNull("当前槽位必须已发布", slot)
            assertSame(
                "快照必须就是槽位里留下的那一份（不是另起一次查询）",
                snapshot,
                p.platform.field(slot, "characteristics")
            )

            // 模拟开关点击/回页补挂：provider 被反复调用。
            repeat(5) {
                assertSame(snapshot, p.platform.call(p.controller, "currentCharacteristics"))
            }
            assertEquals(
                "attach 路径（Main）不许再碰 CameraManager.getCameraCharacteristics",
                afterEnum,
                queries(p)
            )
        }
    }

    // ------------------------------------------------------------------ 接线守卫

    private val ctrlSrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("camera/CameraController.kt"))
    }
    private val lensSrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("core/LensSlot.kt"))
    }
    private val screenSrc by lazy {
        flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")))
    }

    private fun snapshotIssues(ctrl: String, lens: String, screen: String): List<String> {
        val bad = ArrayList<String>()
        val current = runCatching { bodyOf(ctrl, "currentCharacteristics") }.getOrNull()
            ?: return listOf("定位不到 CameraController.currentCharacteristics 的体——守卫失去输入，不许算通过")
        if (occurrences(current, "getCameraCharacteristics").isNotEmpty()) {
            bad.add("取当前特性仍在调用线程查 CameraManager——开关点击/回页补挂会卡 Main")
        }
        if (occurrences(current, "cameraManager()").isNotEmpty()) {
            bad.add("取当前特性仍走 cameraManager()——attach 路径没有离开相机服务")
        }
        if (occurrences(current, "currentSlot?.characteristics").isEmpty()) {
            bad.add("没有读枚举期留下的 characteristics 快照")
        }
        if (occurrences(lens, "var characteristics: CameraCharacteristics?").isEmpty()) {
            bad.add("LensSlot 没有 characteristics 快照字段——枚举期的读取无处可留")
        }
        if (occurrences(lens, "val characteristics: CameraCharacteristics,").isEmpty()) {
            bad.add("LensEntry 没把枚举读到的 characteristics 带进归档")
        }
        if (occurrences(lens, "it.characteristics = characteristics").isEmpty()) {
            bad.add("枚举没有把 characteristics 装进槽位——快照字段永远是 null")
        }
        if (occurrences(screen, "characteristicsProvider = { ctrl.currentCharacteristics() }").isEmpty()) {
            bad.add("宿主的 characteristicsProvider 注入点变了——守卫失去目标，重挂一次就要重新评估")
        }
        return bad
    }

    @Test
    fun `当前特性只读枚举快照`() {
        assertEquals(
            "Y08 接线缺件：\n${snapshotIssues(ctrlSrc, lensSrc, screenSrc).joinToString("\n")}",
            emptyList<String>(),
            snapshotIssues(ctrlSrc, lensSrc, screenSrc)
        )
    }

    @Test
    fun `尺子自己能红_退回同步查询或丢掉快照都必须报红`() {
        assertTrue(snapshotIssues(ctrlSrc, lensSrc, screenSrc).isEmpty())

        // 突变 1：退回同步查相机服务（缺陷原形）。
        val syncQuery = ctrlSrc.replace(
            "return ui.value.currentSlot?.characteristics",
            "return ui.value.currentSlot?.let { cameraManager().getCameraCharacteristics(it.logicId) }"
        )
        assertTrue(
            "同步查询必须红",
            snapshotIssues(syncQuery, lensSrc, screenSrc).any { it.contains("CameraManager") }
        )

        // 突变 2：快照永远拿不到。
        val noSnapshot = ctrlSrc.replace(
            "return ui.value.currentSlot?.characteristics",
            "return null"
        )
        assertTrue(
            "不读快照必须红",
            snapshotIssues(noSnapshot, lensSrc, screenSrc).any { it.contains("快照") }
        )

        // 突变 3：枚举不再把 characteristics 装进槽位。
        val dropped = lensSrc.replace("it.characteristics = characteristics", "Unit")
        assertTrue(
            "枚举丢快照必须红",
            snapshotIssues(ctrlSrc, dropped, screenSrc).any { it.contains("装进槽位") }
        )

        // 突变 4：槽位字段改名（快照消失）。
        val renamed = lensSrc.replace(
            "var characteristics: CameraCharacteristics? = null",
            "var cachedCc: CameraCharacteristics? = null"
        )
        assertTrue(
            "槽位没有快照字段必须红",
            snapshotIssues(ctrlSrc, renamed, screenSrc).any { it.contains("LensSlot") }
        )
    }

    // ------------------------------------------------------------------ 夹具

    private fun queries(p: CameraRecoveryStartFixture): Int =
        p.platform.call(p.manager, "queries") as Int

    companion object {
        /**
         * 与 [CameraRecoveryStartFixture] 默认 CameraManager 桩逐字一致，只多一枚
         * `characteristicsQueries` 计数（`queries()` 读它）。计数放在可用性判断之前：
         * 枚举会探测数字隐藏 id（1..9），试探次数也算 — 这里要的是"attach 没再进来"。
         */
        private val COUNTING_MANAGER = """
            package android.hardware.camera2;
            public class CameraManager {
                public boolean available=true, rejectOpen;
                public int characteristicsQueries;
                private final java.util.ArrayList<CameraDevice.StateCallback> callbacks=new java.util.ArrayList<>();
                private final java.util.ArrayList<CameraDevice> devices=new java.util.ArrayList<>();
                private final java.util.ArrayList<android.hardware.camera2.params.SessionConfiguration> configs=new java.util.ArrayList<>();
                private final java.util.ArrayList<CameraCaptureSession> sessions=new java.util.ArrayList<>();
                public int queries(){return characteristicsQueries;}
                public String[] getCameraIdList(){return available?new String[]{"0"}:new String[0];}
                public CameraCharacteristics getCameraCharacteristics(String id){
                    characteristicsQueries++;
                    if(!available||!id.equals("0"))throw new IllegalArgumentException("camera unavailable: "+id);
                    return new CameraCharacteristics();
                }
                public void openCamera(String id,CameraDevice.StateCallback callback,android.os.Handler handler){
                    if(!id.equals("0"))throw new AssertionError("unexpected camera: "+id);
                    callbacks.add(callback);devices.add(new CameraDevice(this));
                    if(rejectOpen)throw new IllegalArgumentException("scripted open failure");
                }
                public int getOpenCount(){return callbacks.size();}
                public int getSessionCount(){return configs.size();}
                public void completeOpen(int index){callbacks.get(index).onOpened(devices.get(index));}
                public void create(android.hardware.camera2.params.SessionConfiguration config){
                    configs.add(config);sessions.add(new CameraCaptureSession());
                }
                public void completeSession(int index){
                    android.hardware.camera2.params.SessionConfiguration config=configs.get(index);
                    config.executor.execute(()->config.callback.onConfigured(sessions.get(index)));
                }
                public java.util.List<String> outputNames(int index){
                    java.util.ArrayList<String> names=new java.util.ArrayList<>();
                    for(android.hardware.camera2.params.OutputConfiguration output:configs.get(index).outputs){names.add(output.surface.toString());}
                    return names;
                }
                public int deviceCloseCount(int index){return devices.get(index).closeCount;}
                public int sessionCloseCount(int index){return sessions.get(index).closeCount;}
            }
        """
    }
}