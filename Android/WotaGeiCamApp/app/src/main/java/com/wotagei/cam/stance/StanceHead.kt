package com.wotagei.cam.stance

/**
 * 判定头 v1（§7：判定头为主，规则兜底，两载体输出接口一致）。
 *
 * 与 PC 端 judge/head.py 同规格：
 * - 二分类好坏 P(good) = sigmoid(coef·x + intercept)，特征 [cx, by, w, h, conf]（顺序钉死）；
 * - 方向是边界外的几何映射（D19 四边距口径），由 [verdictForOne] 导出，不由头学；
 * - **头只放宽不收紧**（用户定案：标好的必须判完成）——头说好 ⇒ OK；头说歪但几何在
 *   标定安全区内 ⇒ OK；头说歪且几何也越界 ⇒ 报几何方向；
 * - β 作用：P(good) ≥ pOkThreshold(β) → OK；β 大 ⇒ 门槛低 ⇒ 判好更宽松。
 * 权重文件为 JSON（train_head.py 产物），不用 pickle（Mimosa 红线 + 端侧零 ML 依赖）。
 * 解析用自带 [StanceJson]（org.json 在 JVM 单测是 stub，且端侧零依赖）。
 */
object StanceHeadSpec {
    /** 特征规格 v1（顺序与 train_head.py 的 FEATURES 钉死一致）。 */
    val FEATURES = listOf("cx", "by", "w", "h", "conf")

    /** β → OK 判定阈值（β 0.05→0.75 严格 … 0.95→0.25 宽松）。 */
    fun pOkThreshold(beta: Float): Float {
        val t = (beta.coerceIn(0.05f, 0.95f) - 0.05f) / 0.90f
        return 0.75f - 0.50f * t
    }
}

class StanceHead(
    val coef: List<Float>,
    val intercept: Float,
    val meta: Map<String, String> = emptyMap(),
) {
    val version: String get() = meta["head_version"] ?: "unknown"

    companion object {
        /** 从 head.json 文本解析（train_head.py 产物：{coef, intercept, meta}）。 */
        fun fromJson(json: String): StanceHead {
            @Suppress("UNCHECKED_CAST")
            val obj = StanceJson.parse(json) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val coefRaw = obj["coef"] as? List<Any?> ?: throw IllegalArgumentException("判定头缺 coef")
            val coef = coefRaw.map { (it as Number).toFloat() }
            require(coef.size == StanceHeadSpec.FEATURES.size) {
                "判定头特征数不匹配: 头 ${coef.size} vs 规格 ${StanceHeadSpec.FEATURES.size}"
            }
            val intercept = (obj["intercept"] as? Number)?.toFloat()
                ?: throw IllegalArgumentException("判定头缺 intercept")
            @Suppress("UNCHECKED_CAST")
            val metaObj = (obj["meta"] as? Map<String, Any?>) ?: emptyMap()
            val meta = linkedMapOf<String, String>()
            for ((k, v) in metaObj) meta[k] = v?.toString() ?: ""
            return StanceHead(coef, intercept, meta)
        }
    }

    /** P(good) = sigmoid(coef·x + intercept)。 */
    fun pGood(box: PersonBox): Float {
        val feats = listOf(box.cx, box.by, box.w, box.h, box.conf)
        var z = intercept
        for ((c, f) in coef.zip(feats)) z += c * f
        return (1f / (1f + kotlin.math.exp(-z)))
    }

    /** 头路径五分类：好坏边界由头判（只放宽），方向由规则几何导出。 */
    fun judge(box: PersonBox, beta: Float = 0.3f, domainScale: Float = 1f): Verdict {
        if (pGood(box) >= StanceHeadSpec.pOkThreshold(beta)) return Verdict.OK
        return verdictForOne(box, domainScale)   // 几何 OK 就 OK（兜底放宽）
    }
}
