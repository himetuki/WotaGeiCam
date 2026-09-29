package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.ZonePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 任务 #74「控件位置编辑结果要持久化」的**真机跨进程**取证（锁屏也能跑：`am instrument` 不需要亮屏）。
 *
 * ## JVM 用例为什么不够
 * `HudLayoutCodecTest` 那批证的是"同一段字节在同一次 JVM 里往返自洽"。它不知道 `commit()` 在真机上
 * 到底返回什么，更不知道进程死了以后那个文件还在不在。用户那句「编辑设置结果要持久化」断的就是**跨进程**，
 * 所以必须换一个进程再读一次。
 *
 * ## 手法：同一条测试类跑两次 `am instrument`
 * - 第一次只跑 `step1_writeProbe`：走生产写入方 [WotaSettings.setHudLayout]（内部是同步 `commit()`），
 *   方法返回后这次 instrument 的进程结束。
 * - 第二次只跑 `step2_readProbe`：**新进程、新 `SharedPreferences` 实例**，内存里没有任何缓存可依赖，
 *   读回来的只可能是磁盘上那份。
 *
 * ## 期望值全是编译进包里的手写常数
 * 不拿"写入进程算出来的结果"传给读取进程比，那等于与实现自比。
 * 落点 `(123,45)`、监看的 `(0,4)`、其余三颗的 `(0,0)/(0,2)/(0,3)`、底栏 `x=-1, y=8`
 * 都是照着 §十五 的默认格推导**手算**出来写死的，schema 或默认表一变这里就该红。
 *
 * ## 不污染用户存档
 * 探针读写独立 prefs 文件 [PROBE_PREFS]，**不是** `wota_settings` ⇒ 用户的设置、收藏、tag 不受影响；
 * [cleanupProbe] 再把探针文件清干净并复验键已消失。生产函数照用不误——`hudLayout/setHudLayout/clearHudLayout`
 * 的 prefs 都是形参，传探针文件进去跑的就是真机上那条真代码。
 *
 * ## 取证输出
 * 本机 ROM 会丢应用自己的 Log 标签，所以逐行 `sendStatus` 打到 `am instrument` 的 stdout（同 CameraEnumTest 的先例）。
 */
@RunWith(AndroidJUnit4::class)
class HudLayoutPersistProbeTest {

    /** 摆位集合取"全开"：探针不依赖 hud_pills 当前值，默认格的推导才有确定结果 */
    private val plan = HudGridPlan(visible = HudEntry.ALL.toSet(), readoutPerRow = 3)

    /**
     * 第 1 步：写三处各有内容的编辑——
     * ① 整枚左 Dock 拖到 (123, 45)；
     * ② 「监看」摆到第 5 行（显式 [GridCell]，即编码里的 `P7:0.4` 那一段）；
     * ③ 底栏落一次位，x 传哨兵（B4 审查 S1：把实测左缘写成绝对 x 就等于把快门横向钉死）。
     */
    @Test
    fun step1_writeProbe() {
        val prefs = probePrefs()
        val table = HudLayoutTable.default()
            .withZonePos(HudZone.LEFT, 123, 45)
            .placeEntryAt(HudEntry.of(CamPill.MONITOR), HudZone.LEFT, 0, MONITOR_CELL, plan)
            .withZonePos(HudZone.BOTTOM, -1, 8)
        val encoded = table.encode()

        line("STEP1_WRITE")
        line("ENCODED=$encoded")
        assertTrue(
            "setHudLayout 的 commit() 返回 false ⇒ 根本没落成盘，生产代码把它当保存回执用",
            WotaSettings.setHudLayout(prefs, table)
        )

        val raw = prefs.getString(WotaSettings.KEY_HUD_LAYOUT, null)
        assertEquals("写进 prefs 的串与 encode() 不同源", encoded, raw)
        assertTrue("落盘的串不是 v2 schema：$raw", raw?.startsWith("v2") == true)
    }

    /** 第 2 步：读。**必须另跑一次 am instrument**，否则证不到跨进程。 */
    @Test
    fun step2_readProbe() {
        val prefs = probePrefs()
        val raw = prefs.getString(WotaSettings.KEY_HUD_LAYOUT, null)
        line("STEP2_READ pid=${android.os.Process.myPid()}")
        line("RAW=$raw")
        assertTrue("新进程里读不到 hud_layout ⇒ 磁盘上根本没有这份东西", raw != null)

        val back = WotaSettings.hudLayout(prefs)
        // ① 先排除"回落默认表"这种假绿：decode 认不出来时也会给你一个表，看着合理但是空的
        assertNotEquals(
            "读回来的是默认表 ⇒ 编辑结果根本没持久化（decode 悄悄回落）",
            HudLayoutTable.default(),
            back
        )
        // ② 容器落点逐条点名
        assertEquals("左 Dock 整枚位置", ZonePlacement(123, 45), back.posOf(HudZone.LEFT))
        val bottom = back.posOf(HudZone.BOTTOM)
        assertEquals("底栏的 x 不许被持久化成绝对值（快门偏心那条老坑）", -1, bottom.xDp)
        assertEquals("底栏落点 y", 8, bottom.yDp)

        // ③ 格子逐颗点名：监看走到 (0,4)，同容器其余三颗**一格不许动**
        val left = back.gridItems(HudZone.LEFT, plan).associate { it.entry to it.cell }
        assertEquals("监看没落在写进去的那一格", MONITOR_CELL, left[HudEntry.of(CamPill.MONITOR)])
        assertEquals(GridCell(0, 0), left[HudEntry.of(CamPill.REFLINE)])
        assertEquals(GridCell(0, 2), left[HudEntry.of(CamPill.CURVE)])
        assertEquals(GridCell(0, 3), left[HudEntry.of(CamPill.FLASH)])
        line("LEFT_CELLS=${left.mapValues { it.value }}")
    }

    /**
     * 第 3 步（**必须显式跑，不能用 `@After`**）：删键 + 复验 + 清空探针文件。
     *
     * 第一版我把清理写成 `@After`，结果 `step1` 收尾时就把探针文件清掉了，`step2` 读到 `RAW=null`
     * ——一次真实的"持久化失败"假象，而缺陷在我的测试里、不在 app 里。这条要记着：
     * **跨进程探针的每一大步之间，任何自动清理都不许存在。**
     */
    @Test
    fun step3_cleanupProbe() {
        val prefs = probePrefs()
        WotaSettings.clearHudLayout(prefs)
        assertNull("clearHudLayout 之后键还在", prefs.getString(WotaSettings.KEY_HUD_LAYOUT, null))
        prefs.edit().clear().commit()
    }

    private fun probePrefs(): SharedPreferences =
        instr.targetContext.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()

    companion object {
        /** 独立探针文件，不是 `wota_settings` */
        private const val PROBE_PREFS = "wota_hud_layout_probe"

        /** 手写期望：监看被摆到左 Dock 第 5 行（col 0 / row 4） */
        private val MONITOR_CELL = GridCell(0, 4)
    }
}
