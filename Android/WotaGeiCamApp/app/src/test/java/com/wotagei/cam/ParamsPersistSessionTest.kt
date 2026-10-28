package com.wotagei.cam

import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect
import com.wotagei.cam.core.RefLineType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.TapPoint
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.ui.ParamsPersist
import com.wotagei.cam.ui.ParamsPersistSession
import com.wotagei.cam.ui.WotaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置持久化核心（用户反馈第 1 条）的 JVM 单测：往返桥 / 影子语义 / 坏值韧性 / 排除守卫 /
 * requestedSize 意图 / AF 保留口径。
 *
 * 「计划 → 行为」的桥是 [WotaSettings.applyDefaults]（冷启恢复链的真实入口，末尾接
 * [ParamsPersist.restore]）与 [ParamsPersistSession.flush]（落盘出口），测试一律走这两座桥，
 * 不直接摸私有编解码——否则删掉运行时的判断分支测试仍全绿。
 */
class ParamsPersistSessionTest {

    /**
     * [WotaParams.makeBusy] 里的 `setArcMergeDeadband(25)` 会发布**进程级**快照
     * ([ArcMergeDeadband.deadband])，没有 teardown 就会污染同 JVM 后续任何读默认死区的用例；
     * 每个用例后复位出厂默认值。
     */
    @After
    fun resetProcessWideDeadband() {
        ArcMergeDeadband.publish(ArcMergeDeadband.DEFAULT)
    }

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    /** 参考线掩码手写常数：十字准线(4) | 安全区域(16) = 20 */
    private val refMask = RefLineType.CROSSHAIR.bit or RefLineType.SAFE_AREA.bit

    /**
     * 把 24 项全调一遍（值全与出厂默认不同）——**必须在会话影子冻结之后调用**，
     * 模拟"进录制页后用户逐项调出这些值"；先调后建会话的话影子=现值，flush 会（正确地）无差异。
     */
    private fun WotaParams.makeBusy() = apply {
        fps.value = fps.value.copy(value = 60)
        shutter.value = shutter.value.copy(value = 8_333_333L)
        bitrate.value = 35_000_000
        refLines.value = refMask
        levelEnabled.value = false
        iso.value = iso.value.copy(value = 800)
        aeMode.value = AeMode.MANUAL
        ev.value = ev.value.copy(value = -2)
        wbMode.value = WbPreset.DAYLIGHT
        kelvin.value = kelvin.value.copy(value = 4000)
        tint.value = tint.value.copy(value = 12)
        zoom.value = zoom.value.copy(value = 2.5f)
        flash.value = Flash.AUTO
        stabilize.value = Stabilize.EIS
        afMode.value = AfMode.MANUAL
        manualFocusDiopter.value = manualFocusDiopter.value.copy(value = 1.5f)
        requestSize(Size(3840, 2160))
        frameEffect.value = FrameEffect.PEAKING
        zebraThreshold.value = 0.95f
        zebraDensity.value = 3
        peakingStrength.value = 0.75f
        peakingColor.value = PeakingColor.BLUE
        lens.value = LensType.TELEPHOTO
        setArcMergeDeadband(25)
        // 2026-10-26：降噪两项也在白名单里，必须一起动到非默认值（否则 flush 集合对不上白名单）
        audioDenoise.value = com.wotagei.cam.core.AudioDenoise.SYSTEM
        videoDenoise.value = com.wotagei.cam.core.VideoDenoise.GPU_BILATERAL
        // 2026-10-27：站位线间距与颜色入档；**线数故意调成非默认**，用来证明它不被落盘
        markerSpread.value = 0.85f
        markerColor.value = com.wotagei.cam.core.MarkerLineColor.CYAN
        markerCount.value = 6
        // 2026-10-27：Glow 整份参数入档（复合串键；开启 + 动一个形状参数足够证往返）
        glowParams.value = com.wotagei.cam.core.GlowParams(enabled = true, threshold = 0.45f, blend = 0.83f)
        // 2026-10-28：色调四滑条入档（复合串键 KEY_TONE；非恒等足够证往返）
        tone.value = com.wotagei.cam.core.ToneParams(black = 0.1f, white = 0.2f, shadows = 0.5f, highlights = -0.4f)
        // 2026-10-27：上屏渲染比例（枚举名）
        displayScale.value = com.wotagei.cam.core.DisplayScale.QUARTER
    }

    /** 空能力假件（走 VideoSizeAspectTest 的手填惯例）；afModes 由用例点菜 */
    private fun abilityWithAf(vararg cam2Modes: Int) = CameraAbility(
        hardwareLevel = 0,
        sensorOrientation = 0,
        activeArray = Rect(0, 0, 0, 0),
        jpegSizes = emptyList(),
        highSpeedSizes = emptyList(),
        videoSizes = emptyList(),
        fpsRanges = emptyList(),
        highSpeedFps = emptyMap(),
        iso = RangeI.CLOSED,
        exposureNs = RangeI.CLOSED,
        evRange = RangeI.CLOSED,
        evStep = 0f,
        afModes = cam2Modes.toSet(),
        awbModes = emptySet(),
        flashAvailable = false,
        maxRegionsAf = 0,
        maxRegionsAe = 0,
        minFocusDistanceDiopter = 0f,
        zoomRatioRange = null,
        maxDigitalZoom = 1f,
        oisAvailable = false,
        eisAvailable = false,
        highSpeedCapable = false,
        availableSurfaceFormats = IntArray(0)
    )

    // -------------------------------------------------- 1. 往返桥

    @Test
    fun `往返桥_三十字段经flush与applyDefaults逐项一致`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)   // 影子先冻结（进录制页）
        bus.makeBusy()                                    // 再逐项调出 28 个非默认值（用户动作）
        assertTrue("全动过的会话 flush() 必须真的落盘", session.flush())
        assertEquals(
            "flush 实写的键集合必须恰好等于白名单（多一个=排他字段泄入，少一个=有字段漏写）",
            ParamsPersist.KEYS.toSet(),
            prefs.getAll().keys
        )

        // 冷启一侧：新总线走真实恢复入口（applyDefaults 末尾接 restore）
        val back = params()
        WotaSettings.applyDefaults(prefs, back)
        assertEquals(60, back.fps.value.value)
        assertEquals(8_333_333L, back.shutter.value.value)
        assertEquals(35_000_000, back.bitrate.value)
        assertEquals(refMask, back.refLines.value)
        assertFalse("水平仪开关也必须往返", back.levelEnabled.value)
        assertEquals(800, back.iso.value.value)
        assertEquals(AeMode.MANUAL, back.aeMode.value)
        assertEquals(-2, back.ev.value.value)
        assertEquals(WbPreset.DAYLIGHT, back.wbMode.value)
        assertEquals(4000, back.kelvin.value.value)
        assertEquals(12, back.tint.value.value)
        assertEquals(2.5f, back.zoom.value.value, 1e-6f)
        assertEquals(Flash.AUTO, back.flash.value)
        assertEquals(Stabilize.EIS, back.stabilize.value)
        assertEquals(AfMode.MANUAL, back.afMode.value)
        assertEquals(1.5f, back.manualFocusDiopter.value.value, 1e-6f)
        assertEquals(Size(3840, 2160), back.requestedSize)
        assertEquals(FrameEffect.PEAKING, back.frameEffect.value)
        assertEquals(0.95f, back.zebraThreshold.value, 1e-6f)
        assertEquals(3, back.zebraDensity.value)
        assertEquals(0.75f, back.peakingStrength.value, 1e-6f)
        assertEquals(PeakingColor.BLUE, back.peakingColor.value)
        assertEquals(LensType.TELEPHOTO, back.lens.value)
        assertEquals("补弧差分死区也必须往返", 25, back.arcMergeDeadband.value)
        assertEquals("音频降噪也必须往返", com.wotagei.cam.core.AudioDenoise.SYSTEM, back.audioDenoise.value)
        assertEquals(
            "画面降噪也必须往返",
            com.wotagei.cam.core.VideoDenoise.GPU_BILATERAL, back.videoDenoise.value
        )
        assertEquals("站位线间距必须往返", 0.85f, back.markerSpread.value, 1e-6f)
        assertEquals(
            "站位线颜色必须往返",
            com.wotagei.cam.core.MarkerLineColor.CYAN, back.markerColor.value
        )
        assertEquals(
            "站位线**线数不入档**：恢复后恒为出厂 0（启动时取景器干净无线）",
            0, back.markerCount.value
        )
        assertEquals(
            "Glow 整份参数必须往返（复合串键）",
            com.wotagei.cam.core.GlowParams(enabled = true, threshold = 0.45f, blend = 0.83f),
            back.glowParams.value
        )
        assertEquals(
            "色调四滑条必须往返（KEY_TONE 独立键，不并进曲线格式）",
            com.wotagei.cam.core.ToneParams(black = 0.1f, white = 0.2f, shadows = 0.5f, highlights = -0.4f),
            back.tone.value
        )
        assertEquals(
            "上屏渲染比例必须往返",
            com.wotagei.cam.core.DisplayScale.QUARTER, back.displayScale.value
        )
    }

    // -------------------------------------------------- 2. 影子语义

    @Test
    fun `影子不覆盖外部改动_录制页没动过的字段不写盘`() {
        val prefs = FakePrefs()
        val bus = params().apply { fps.value = fps.value.copy(value = 60) }
        val session = ParamsPersistSession(prefs, bus)
        // 用户在设置页把帧率改到 24（总线还停在录制页的 60——applyDefaultsOnce 进程内只套一次）
        prefs.map[WotaSettings.KEY_DEFAULT_FPS] = 24
        // 录制页随后只动了码率
        bus.bitrate.value = 20_000_000
        assertTrue(session.flush())
        assertEquals(
            "录制页没动过帧率，陈旧总线 60 不许把外部改的 24 覆盖掉",
            24, prefs.getInt(WotaSettings.KEY_DEFAULT_FPS, -1)
        )
        assertEquals(20_000_000, prefs.getInt(WotaSettings.KEY_DEFAULT_BITRATE, -1))
        assertEquals("只该写码率一个键，恰好一次 commit", 1, prefs.commitCount)
    }

    @Test
    fun `无差异不写盘_不产生commit也不触发监听`() {
        val prefs = FakePrefs()
        val session = ParamsPersistSession(prefs, params())
        assertTrue(session.flush())
        assertTrue(session.flush())
        assertEquals("无差异的 flush 不许 commit", 0, prefs.commitCount)
        assertEquals("无差异的 flush 不许 apply", 0, prefs.applyCount)
        assertTrue("无差异的 flush 不许写任何键", prefs.getAll().isEmpty())
    }

    @Test
    fun `写盘成功后影子前进_再flush不再重复写`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)
        bus.iso.value = bus.iso.value.copy(value = 1600)
        assertTrue(session.flush())
        assertEquals(1, prefs.commitCount)
        assertTrue("同一值再 flush 一次不该有差异", session.flush())
        assertEquals("影子没前进就会每次 flush 都写盘", 1, prefs.commitCount)
    }

    // -------------------------------------------------- 3. LOCK 归一（Q5）

    @Test
    fun `LOCK一律存为AUTO_不跨进程不自动锁曝光`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)
        bus.aeMode.value = AeMode.MANUAL
        assertTrue(session.flush())
        assertEquals("MANUAL 必须能跨进程", "MANUAL", prefs.getString(WotaSettings.KEY_DEFAULT_AE_MODE, null))

        // 长按锁 AE 的终态：总线落成 LOCK（状态机 AUTO→LOCK），落盘侧必须归一
        bus.aeMode.value = AeMode.LOCK
        assertTrue(session.flush())
        assertEquals(
            "LOCK 是瞬时锁，入档一律归一为 AUTO",
            "AUTO", prefs.getString(WotaSettings.KEY_DEFAULT_AE_MODE, null)
        )

        // 恢复侧同样归一：手写进 prefs 的 "LOCK" 与坏串都回 AUTO
        prefs.map[WotaSettings.KEY_DEFAULT_AE_MODE] = "LOCK"
        val revive = params()
        ParamsPersist.restore(prefs, revive)
        assertEquals(AeMode.AUTO, revive.aeMode.value)
        prefs.map[WotaSettings.KEY_DEFAULT_AE_MODE] = "??? "
        val revive2 = params()
        ParamsPersist.restore(prefs, revive2)
        assertEquals(AeMode.AUTO, revive2.aeMode.value)
    }

    // -------------------------------------------------- 4. 坏值/越界韧性

    @Test
    fun `坏值与越界逐项回落或钳制_绝不抛`() {
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_DEFAULT_FPS] = 9999
        prefs.map[WotaSettings.KEY_DEFAULT_SHUTTER_NS] = -1
        prefs.map[WotaSettings.KEY_DEFAULT_ISO] = -5
        prefs.map[WotaSettings.KEY_DEFAULT_WB_PRESET] = "???"
        prefs.map[WotaSettings.KEY_DEFAULT_ZOOM] = -2f
        prefs.map[WotaSettings.KEY_DEFAULT_FLASH] = "FLASHBANG"
        prefs.map[WotaSettings.KEY_DEFAULT_STABILIZE] = "99"
        prefs.map[WotaSettings.KEY_DEFAULT_AF_MODE] = "NO_SUCH_MODE"
        prefs.map[WotaSettings.KEY_DEFAULT_MF_DIOPTER] = -0.5f
        prefs.map[WotaSettings.KEY_DEFAULT_SIZE] = "abc"
        prefs.map[WotaSettings.KEY_DEFAULT_FRAME_EFFECT] = ""
        prefs.map[WotaSettings.KEY_DEFAULT_ZEBRA_THRESHOLD] = 5f
        prefs.map[WotaSettings.KEY_DEFAULT_ZEBRA_DENSITY] = 9
        prefs.map[WotaSettings.KEY_DEFAULT_PEAKING_STRENGTH] = -3f
        prefs.map[WotaSettings.KEY_DEFAULT_PEAKING_COLOR] = "LAVA"
        prefs.map[WotaSettings.KEY_DEFAULT_LENS] = "PERISCOPE"
        prefs.map[WotaSettings.KEY_DEFAULT_MARKER_SPREAD] = 9f
        prefs.map[WotaSettings.KEY_DEFAULT_MARKER_COLOR] = "PLAID"

        val p = params()
        ParamsPersist.restore(prefs, p)
        assertEquals("fps 越界上端钳回产品档", WotaTiers.FPS.last(), p.fps.value.value)
        assertEquals("快门坏值跳过 → 出厂默认", 40_000_000L, p.shutter.value.value)
        assertEquals("ISO 坏值跳过 → 出厂默认", 100, p.iso.value.value)
        assertEquals(WbPreset.AUTO, p.wbMode.value)
        assertEquals("变焦坏值跳过 → 出厂默认", 1f, p.zoom.value.value, 1e-6f)
        assertEquals(Flash.OFF, p.flash.value)
        assertEquals(Stabilize.OFF, p.stabilize.value)
        assertEquals(AfMode.CONTINUOUS_VIDEO, p.afMode.value)
        assertEquals("屈光度负数归 0", 0f, p.manualFocusDiopter.value.value, 1e-6f)
        assertEquals("尺寸坏串跳过 → 出厂默认", Size(1920, 1080), p.requestedSize)
        assertEquals(FrameEffect.NONE, p.frameEffect.value)
        assertEquals("斑马阈值钳进产品域", 1f, p.zebraThreshold.value, 1e-6f)
        assertEquals("斑马密度钳 1..3", 3, p.zebraDensity.value)
        assertEquals("峰值强度钳 0..1", 0f, p.peakingStrength.value, 1e-6f)
        assertEquals(PeakingColor.RED, p.peakingColor.value)
        assertEquals(LensType.WIDE, p.lens.value)
        assertEquals(
            "站位线间距越界钳回产品域上限", com.wotagei.cam.core.MarkerLines.MAX_SPREAD,
            p.markerSpread.value, 1e-6f
        )
        assertEquals(
            "站位线颜色坏串回黄（与色板 fromOrdinal 兜底同取向）",
            com.wotagei.cam.core.MarkerLineColor.YELLOW, p.markerColor.value
        )

        // 下端同样有执行力：fps 太小钳回首档；合法尺寸串真的能套上
        val low = FakePrefs()
        low.map[WotaSettings.KEY_DEFAULT_FPS] = 1
        low.map[WotaSettings.KEY_DEFAULT_SIZE] = "1280x720"
        val p2 = params()
        ParamsPersist.restore(low, p2)
        assertEquals(WotaTiers.FPS.first(), p2.fps.value.value)
        assertEquals(Size(1280, 720), p2.requestedSize)
    }

    // -------------------------------------------------- 5. 排除清单（自动降级/音源/瞬时态）

    @Test
    fun `上屏档坏串恢复回原片`() {
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_DISPLAY_SCALE] = "SUPER_LOW"   // 手改 prefs 的未知枚举名
        val p = params().apply { displayScale.value = com.wotagei.cam.core.DisplayScale.EIGHTH }
        ParamsPersist.restore(prefs, p)
        assertEquals(
            "坏串必须回原片（枚举名解析失败不许猜一档）",
            com.wotagei.cam.core.DisplayScale.ORIG, p.displayScale.value
        )
        // 合法串要读得回来（否则每次启动都回原片）
        prefs.map[WotaSettings.KEY_DISPLAY_SCALE] = com.wotagei.cam.core.DisplayScale.HALF.name
        ParamsPersist.restore(prefs, p)
        assertEquals(com.wotagei.cam.core.DisplayScale.HALF, p.displayScale.value)
        // 缺键 = 从没改过 ⇒ 原片
        prefs.map.remove(WotaSettings.KEY_DISPLAY_SCALE)
        ParamsPersist.restore(prefs, p)
        assertEquals(com.wotagei.cam.core.DisplayScale.ORIG, p.displayScale.value)
    }

    @Test
    fun `自动降级与音源瞬时态都不入档`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)
        bus.makeBusy()
        // 三类"绝对不能持久化"的写点逐一来一遍
        bus.audioEnabled.value = false                      // 麦克风权限缺失的降级
        bus.renderMode.value = RenderMode.DIRECT            // ※ 档自动切 GPU / 设置页独占
        bus.sampleRate.value = 32_000                       // 设置页独占
        bus.focusPoint.value = TapPoint(0.4f, 0.6f)         // 瞬时点按态
        assertTrue(session.flush())
        val keys = prefs.getAll().keys
        assertEquals("实写集合必须恰等于白名单（不得多写）", ParamsPersist.KEYS.toSet(), keys)
        for (k in listOf(
            WotaSettings.KEY_DEFAULT_AUDIO_MUTE,
            WotaSettings.KEY_DEFAULT_RENDER,
            WotaSettings.KEY_DEFAULT_SAMPLE_RATE,
            WotaSettings.KEY_CURVE_STACK,
            WotaSettings.KEY_ARC_CONVERT
        )) {
            assertFalse("$k 在排除清单里（降级/专属链），任何 flush 都不许写它", keys.contains(k))
        }
    }

    @Test
    fun `音源开关动了也不鸣笛_降级触发的flush全为无差异`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)
        // 音源 toggle（onToggleAmbient）与降级（applyAudioPermission）都只写总线
        bus.audioEnabled.value = false
        assertTrue(session.flush())
        assertEquals("音源不进白名单，flush 应是无差异 no-op", 0, prefs.commitCount)
    }

    // -------------------------------------------------- 6. requestedSize 存意图

    @Test
    fun `分辨率存的是意图值不是生效值`() {
        val prefs = FakePrefs()
        val bus = params()
        val session = ParamsPersistSession(prefs, bus)
        // 生效尺寸被引擎/能力改小（比如换镜头落档），意图不动：不许入档
        bus.size.value = Size(640, 480)
        assertTrue(session.flush())
        assertNull(
            "总线 size 是「当前生效」不是真源，flush 该无差异",
            prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null)
        )
        // UI 改档走 requestSize：意图+生效一起动，这次必须落盘
        bus.requestSize(Size(3840, 2160))
        assertTrue(session.flush())
        assertEquals("3840x2160", prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null))
        // 恢复侧写回的是意图（requestSize 同时把生效也落上，等 applySize 按新镜头能力重落档）
        val back = params()
        ParamsPersist.restore(prefs, back)
        assertEquals(Size(3840, 2160), back.requestedSize)
        assertEquals(Size(3840, 2160), back.size.value)
    }

    // -------------------------------------------------- 7. AF 保留口径（Q3/T3）

    @Test
    fun `applyFocus能力含当前档则保留否则回落PREFERENCE`() {
        // 能力含 MANUAL(OFF)：用户设的 MANUAL 换挡后保留（持久化 AF 模式能生效的前提）
        val keep = params().apply { afMode.value = AfMode.MANUAL }
        keep.applyAbility(abilityWithAf(AfMode.MANUAL.cam2Mode, AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertEquals(AfMode.MANUAL, keep.afMode.value)

        // 能力不含 MANUAL：回落 PREFERENCE 首档（连续视频）
        val fallback = params().apply { afMode.value = AfMode.MANUAL }
        fallback.applyAbility(abilityWithAf(AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertEquals(AfMode.CONTINUOUS_VIDEO, fallback.afMode.value)

        // 正对照：连续档在能力内原样保留（新口径对既有常态是恒等）
        val cont = params()
        cont.applyAbility(abilityWithAf(AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertEquals(AfMode.CONTINUOUS_VIDEO, cont.afMode.value)

        // 定焦镜头：afModes 只含 OFF 时 PREFERENCE 依次落不动、兜底 MANUAL
        val fixed = params()
        fixed.applyAbility(abilityWithAf(AfMode.MANUAL.cam2Mode))
        assertEquals(AfMode.MANUAL, fixed.afMode.value)

        // 无 AF 能力表：直接 MANUAL 兜底
        val none = params()
        none.applyAbility(abilityWithAf())
        assertEquals(AfMode.MANUAL, none.afMode.value)
    }

    // -------------------------------------------------- 8. R1 后设置页行为 + 状态位纪律

    @Test
    fun `R1后设置页两行已删_但存量键仍由恢复链生效`() {
        // 设置页不再有帧率/快门写点（守卫测试钉源码），键本身必须照常工作：
        // 录制页 flush 写进去的值，冷启必须读回来
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_DEFAULT_FPS] = 50
        prefs.map[WotaSettings.KEY_DEFAULT_SHUTTER_NS] = 20_000_000
        val p = params()
        WotaSettings.applyDefaults(prefs, p)
        assertEquals(50, p.fps.value.value)
        assertEquals(20_000_000L, p.shutter.value.value)
    }

    @Test
    fun `恢复只改值不碰状态位`() {
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_DEFAULT_ISO] = 6400
        prefs.map[WotaSettings.KEY_DEFAULT_FPS] = 30
        val p = params()
        // 模拟能力换挡已经写进来的 range/enabled/exact：恢复不许抹掉它们
        p.iso.value = ParamState(100, 50..12800, enabled = true, exact = false)
        p.fps.value = ParamState(25, 24..30, enabled = true, exact = true)
        ParamsPersist.restore(prefs, p)
        assertEquals(6400, p.iso.value.value)
        assertEquals(50..12800, p.iso.value.range)
        assertFalse("exact 是能力换挡的产物，恢复不许改", p.iso.value.exact)
        assertEquals(30, p.fps.value.value)
        assertEquals(24..30, p.fps.value.range)
        assertTrue(p.fps.value.enabled)
    }
}
