package com.wotagei.cam.ui.widget

/**
 * 「滑杆吸附 / 连续值」的纯逻辑层（2026-10-21 用户反馈：变焦只能落 0.5/1/1.5…，
 * 想调 1.2×、1.7× 调不了 ⇒ 设置页逐个参数关掉刻度吸附）。
 *
 * 只放**没有梯**的参数：曝光补偿（整数 EV）、斑马纹阈值（整数 %）、并入门槛（整数 D）
 * 的取值域本身就是逐格整数，连续模式与梯完全等价，放进来只会让人以为开关有作用。
 */
enum class SliderKey(val id: String) {
    SHUTTER("shutter"),
    ISO("iso"),
    KELVIN("kelvin"),
    TINT("tint"),
    ZOOM("zoom"),
    DIOPTER("diopter"),
    PEAKING("peaking"),
}

/**
 * 连续值集合 → prefs 字符串（纯函数，JVM 全测）：按 [SliderKey] 声明顺序、逗号分隔，
 * 同一集合恒得到同一串（便于比对与人工排查）。id 是持久化契约，**只许追加、不许改名**。
 */
internal fun encodeContinuousSliders(keys: Set<SliderKey>): String =
    SliderKey.entries.filter { it in keys }.joinToString(",") { it.id }

/** prefs 字符串 → 连续值集合：空/坏串 → 空集（= 全部吸附），未知 id 忽略（版本回退不炸） */
internal fun decodeContinuousSliders(raw: String?): Set<SliderKey> {
    if (raw.isNullOrBlank()) return emptySet()
    val byId = SliderKey.entries.associateBy { it.id }
    return raw.split(',').mapNotNull { byId[it.trim()] }.toSet()
}

/**
 * 桥函数（纯，JVM 全测）：吸附开关 × 梯 → [PillSlider] 实际使用的梯。
 * `continuous = true`（用户在设置页关掉了该参数的吸附）→ null，滑杆退回 `lo..hi` 连续区间；
 * 否则原样返回梯（梯为空/单点时由 PillSlider 判为不可用并禁用，语义不变）。
 *
 * 单独成函数是为了让「配置 → 行为」这句判断能被单测直接打：把开关接进 UI 却漏掉这里，
 * 或者反过来（读了开关却没用），都能用突变体抓出来。
 */
internal fun effectiveLadder(ladder: List<Float>, continuous: Boolean): List<Float>? =
    if (continuous) null else ladder
