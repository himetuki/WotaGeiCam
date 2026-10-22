package com.wotagei.cam

import com.wotagei.cam.camera.buddiesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [com.wotagei.cam.camera.DualCameraProbe] 里可离线测的那一半：
 * `Set<Set<String>> 组合表 + 目标 id → 同组伙伴`。
 *
 * ⚠ 覆盖边界写清：`concurrentCameraIdsWith` 的 **API 30 分级**与 **异常吞掉**在 JVM 里
 * 都跑不到（没有 Android 的 `Build`/`CameraManager`，本工程也无 Robolectric），
 * 那两条只能由真机 / 集成覆盖；这里只钉组合解析这一层纯逻辑。
 */
class DualCameraProbeTest {

    /** 空组合表：没有任何并发组合 ⇒ 伙伴集为空（等价"本机不支持并发"） */
    @Test
    fun `emptyCombosMeansNoBuddy`() {
        assertEquals(emptySet<String>(), buddiesOf(emptySet<Set<String>>(), "0"))
    }

    /** 含目标的双摄组合：伙伴就是另外那一颗 */
    @Test
    fun `pairWithTargetGivesTheOtherId`() {
        assertEquals(setOf("1"), buddiesOf(setOf(setOf("0", "1")), "0"))
    }

    /** 含目标的三摄组合：伙伴是另外两颗（不是只取第一颗） */
    @Test
    fun `tripleWithTargetGivesBothOthers`() {
        assertEquals(setOf("1", "2"), buddiesOf(setOf(setOf("0", "1", "2")), "0"))
    }

    /** 不含目标的组合整组不参与，哪怕它是并发组合 */
    @Test
    fun `combosWithoutTargetAreIgnored`() {
        assertEquals(
            emptySet<String>(),
            buddiesOf(setOf(setOf("1", "2")), "0")
        )
    }

    /** 只有目标自己的 size==1 组合不算并发伙伴（并发至少两路） */
    @Test
    fun `singletonComboIsNotConcurrency`() {
        assertEquals(emptySet<String>(), buddiesOf(setOf(setOf("0")), "0"))
    }

    /** 目标出现在多个组合里时取并集 */
    @Test
    fun `multipleCombosUnionTheirBuddies`() {
        assertEquals(
            setOf("1", "2", "3"),
            buddiesOf(setOf(setOf("0", "1", "2"), setOf("0", "2", "3")), "0")
        )
    }

    /** 目标哪一组都不在 ⇒ 空集 */
    @Test
    fun `targetMissingEverywhereGivesEmpty`() {
        assertEquals(
            emptySet<String>(),
            buddiesOf(setOf(setOf("1", "2"), setOf("3", "4")), "0")
        )
    }
}
