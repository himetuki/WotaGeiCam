package com.wotagei.cam.core

/**
 * RGB 曲线的纯模型（需求「RGB 曲线（白、蓝、绿、红）」，00 文档把实现排在 v0.0.2 的 T5）。
 *
 * 只做纯函数：控制点 → 256 档查表。GL 侧把表上传成一维纹理后在片元里查，
 * 所以这里必须保证「同一输入恒得同一输出」且「表值恒在 0..1」，否则烘焙结果会闪。
 *
 * 通道语义按调色惯例：先过 [MASTER]（白），再分别过 R/G/B。
 * 蓝通道曲线 = 只改 B 分量，与需求里「白、蓝、绿、红」四条对应。
 */
data class ColorCurve(val points: List<Point>) {

    /** 一个控制点；x 与 y 都在 [0,1] */
    data class Point(val x: Float, val y: Float)

    companion object {
        /** 查表精度：256 档对应 8bit 输入，肉眼无台阶且纹理一行放得下 */
        const val TABLE_SIZE = 256

        val IDENTITY = ColorCurve(emptyList())

        /** 需求里「白/红/绿/蓝」四条曲线的槽位 */
        const val CHANNEL_MASTER = 0
        const val CHANNEL_RED = 1
        const val CHANNEL_GREEN = 2
        const val CHANNEL_BLUE = 3

        /**
         * 分段线性插值。控制点按 x 升序；x 相同视为后一个覆盖前一个，避免除零。
         * 首点之前取首点 y、末点之后取末点 y（钳制，不外推，防止曲线甩出合法域）。
         */
        fun sample(points: List<Point>, x: Float): Float {
            if (points.isEmpty()) return x.clamp01()
            if (points.size == 1) return points[0].y.clamp01()
            val v = x.clamp01()
            if (v <= points.first().x) return points.first().y.clamp01()
            if (v >= points.last().x) return points.last().y.clamp01()
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                if (v in a.x..b.x) {
                    val span = b.x - a.x
                    if (span <= 0f) return b.y.clamp01()
                    return (a.y + (b.y - a.y) * ((v - a.x) / span)).clamp01()
                }
            }
            return v
        }

        private fun Float.clamp01(): Float = if (this < 0f) 0f else if (this > 1f) 1f else this

        /** 解析失败一律回落恒等：持久化串是用户数据，坏值不能把 App 崩掉 */
        fun decode(raw: String?): ColorCurve {
            if (raw.isNullOrEmpty()) return IDENTITY
            val pts = raw.split(",").mapNotNull { seg ->
                val kv = seg.split(":")
                if (kv.size != 2) return@mapNotNull null
                val x = kv[0].toFloatOrNull() ?: return@mapNotNull null
                val y = kv[1].toFloatOrNull() ?: return@mapNotNull null
                // 防御：外部损坏的持久串可能带 NaN/Infinity 记号（toFloatOrNull 接受它们），
                // coerceIn 对 NaN 原样放行 ⇒ NaN 控制点会烘出全黑 LUT。唯一写入方 encode() 永不
                // 产出这些记号，这里只拦畸形输入
                if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                Point(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            }
            return ColorCurve(pts.sortedBy { it.x })
        }
    }

    /** 归一化输入 → 归一化输出；空点集即直通 */
    fun evaluate(x: Float): Float = sample(points, x)

    /** 烘焙成 [TABLE_SIZE] 查表，索引 i 对应输入 i/(TABLE_SIZE-1) */
    fun table(size: Int = TABLE_SIZE): FloatArray =
        FloatArray(size) { i -> evaluate(i.toFloat() / (size - 1)) }

    /** 是否等价于直通：无控制点，或所有控制点都落在 y=x 上 */
    fun isIdentity(): Boolean = points.all { it.x == it.y }

    /** `x:y` 逗号串；空串表示恒等，供设置页持久化 */
    fun encode(): String =
        points.joinToString(",") { "${fmt(it.x)}:${fmt(it.y)}" }
}

private fun fmt(v: Float): String = java.util.Locale.US.let { String.format(it, "%.3f", v) }

/**
 * 四条曲线的组合（白 / 红 / 绿 / 蓝），一次烘焙出 GL 直接可用的 `TABLE_SIZE*3` 张量。
 * 张量按 GL_RGB **交错**布局存放：第 i 个 texel 连续携带 (R[i], G[i], B[i]) 三分量，
 * 一次 `glTexImage2D` 即可上传成 256×1 的 RGB 纹理。
 * 字节布局与 `GlRenderEngine.ensureCurveTexture` 的消费语义是同一个契约，两边不许各写一遍——改一处必须同步另一处。
 * （2026-10-03 真机花图缺陷即此处曾按平面三段产出、消费侧按交错解释成乱表，取证 `.tmp/curve-corruption.md`。）
 */
data class CurveStack(
    val master: ColorCurve = ColorCurve.IDENTITY,
    val red: ColorCurve = ColorCurve.IDENTITY,
    val green: ColorCurve = ColorCurve.IDENTITY,
    val blue: ColorCurve = ColorCurve.IDENTITY
) {

    val isPassthrough: Boolean
        get() = master.isIdentity() && red.isIdentity() && green.isIdentity() && blue.isIdentity()

    /** 按通道槽位取对应的曲线 */
    fun curveOf(channel: Int): ColorCurve = when (channel) {
        ColorCurve.CHANNEL_RED -> red
        ColorCurve.CHANNEL_GREEN -> green
        ColorCurve.CHANNEL_BLUE -> blue
        else -> master
    }

    /** 替换单条通道，其余通道保持不动 */
    fun withCurve(channel: Int, curve: ColorCurve): CurveStack = when (channel) {
        ColorCurve.CHANNEL_RED -> copy(red = curve)
        ColorCurve.CHANNEL_GREEN -> copy(green = curve)
        ColorCurve.CHANNEL_BLUE -> copy(blue = curve)
        else -> copy(master = curve)
    }

    /** 单通道求值：先白后本色，顺序不能换（白曲线是全局对比度，本色是通道增益） */
    fun evaluate(channel: Int, x: Float): Float {
        val afterMaster = master.evaluate(x)
        return when (channel) {
            ColorCurve.CHANNEL_RED -> red.evaluate(afterMaster)
            ColorCurve.CHANNEL_GREEN -> green.evaluate(afterMaster)
            ColorCurve.CHANNEL_BLUE -> blue.evaluate(afterMaster)
            else -> afterMaster
        }
    }

    /**
     * 烘焙成 `size*3` 的 RGB 张量，供一维纹理上传。
     * 布局必须是**交错**的：out[3i]=R[i]、out[3i+1]=G[i]、out[3i+2]=B[i]——
     * GL 侧按 256×1 GL_RGB 纹理上传后，shader 对同一 texel 取 .r/.g/.b 当作对应通道的查表值
     * （见 `Shaders.CURVE_DECL` 的 `applyCurve`）。若按平面三段（R‖G‖B）产出，
     * GPU 把乱序字节当交错读，每通道变成三轮 0→255 锯齿 ⇒ 花图。
     */
    fun bake(size: Int = ColorCurve.TABLE_SIZE): FloatArray {
        val out = FloatArray(size * 3)
        for (i in 0 until size) {
            val v = i.toFloat() / (size - 1)
            out[3 * i] = evaluate(ColorCurve.CHANNEL_RED, v)
            out[3 * i + 1] = evaluate(ColorCurve.CHANNEL_GREEN, v)
            out[3 * i + 2] = evaluate(ColorCurve.CHANNEL_BLUE, v)
        }
        return out
    }

    /**
     * 上传给 `glTexImage2D` 的 256×1 RGB 纹理数据：字节布局 = [bake] 的交错布局量化到 8bit
     * （唯一消费点是 `GlRenderEngine.ensureCurveTexture`，契约以那边的上传/采样语义为准，两边不许各写一遍）。
     * 必须走 UNSIGNED_BYTE：GLES 2.0 里 `GL_RGB + GL_FLOAT` 不是合法组合格式，
     * 只有 `GL_LUMINANCE`/`GL_ALPHA` 才允许浮点，直接传 FloatBuffer 会在部分 ROM 上出黑屏。
     */
    fun bakeBytes(size: Int = ColorCurve.TABLE_SIZE): ByteArray {
        val f = bake(size)
        return ByteArray(f.size) { i -> ((f[i] * 255f) + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }

    /** 四段 `x:y` 串按 `;` 分隔（段序与 [ColorCurve.CHANNEL_*] 一致） */
    fun encode(): String = listOf(master, red, green, blue).joinToString(";") { it.encode() }

    companion object {
        val IDENTITY = CurveStack()

        /** 段数不对或含坏值一律回落恒等 */
        fun decode(raw: String?): CurveStack {
            if (raw.isNullOrEmpty()) return IDENTITY
            val seg = raw.split(";")
            if (seg.size != 4) return IDENTITY
            val stack = CurveStack(
                master = ColorCurve.decode(seg[0]),
                red = ColorCurve.decode(seg[1]),
                green = ColorCurve.decode(seg[2]),
                blue = ColorCurve.decode(seg[3])
            )
            return stack
        }
    }
}

/**
 * 曲线编辑器的纯逻辑：命中测试、增删改控制点。
 *
 * 端点（x=0 与 x=1）恒在且横向锁定，中间点只能插在两个邻居之间，所以点集永远严格升序——
 * [ColorCurve.sample] 的除零保护因此不会被 UI 触到。全部数值都在 0..1，可 JVM 单测。
 */
object CurveEdit {

    /** 单条通道最多几个控制点：再多折线就成台阶了 */
    const val MAX_POINTS = 8

    /** 相邻控制点的最小横向间距，避免拖出零宽线段 */
    const val MIN_DX = 0.04f

    /** 触点判定半径（归一化到画布宽高的比例，与像素无关，便于单测） */
    const val HIT_RADIUS = 0.07f

    /** 编辑器看到的点集：补齐缺失的端点，恒等曲线即 (0,0)-(1,1) 两点 */
    fun pointsOf(curve: ColorCurve): List<ColorCurve.Point> {
        val src = curve.points
        if (src.isEmpty()) return listOf(ColorCurve.Point(0f, 0f), ColorCurve.Point(1f, 1f))
        val head = if (src.first().x == 0f) src else listOf(ColorCurve.Point(0f, src.first().y)) + src
        val tail = if (head.last().x == 1f) head else head + listOf(ColorCurve.Point(1f, head.last().y))
        return tail.sortedBy { it.x }
    }

    /** 最近的点；超出 [HIT_RADIUS] 返回 -1（表示点的是空白，应新增控制点） */
    fun hitTest(points: List<ColorCurve.Point>, x: Float, y: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = kotlin.math.hypot((p.x - x).toDouble(), (p.y - y).toDouble())
            if (d <= HIT_RADIUS && d < bestDist) {
                bestDist = d.toFloat()
                best = i
            }
        }
        return best
    }

    /** 拖动第 [index] 个点：端点只跟 Y，中间点 X 被夹在两个邻居之间 */
    fun moved(points: List<ColorCurve.Point>, index: Int, x: Float, y: Float): List<ColorCurve.Point> {
        if (index !in points.indices) return points
        val last = points.size - 1
        val fixed = index == 0 || index == last
        val lo = if (index == 0) 0f else points[index - 1].x + MIN_DX
        val hi = if (index == last) 1f else points[index + 1].x - MIN_DX
        val cur = points[index]
        val nx = when {
            fixed -> cur.x
            lo <= hi -> x.coerceIn(lo, hi)
            else -> cur.x     // 邻居挤到没有可放空间：保持原位
        }
        val ny = y.coerceIn(0f, 1f)
        return points.toMutableList().also { it[index] = ColorCurve.Point(nx, ny) }
    }

    /**
     * 在空白处新增控制点，返回新点集与选中下标；已到上限或**两邻居间没有合法插缝**返回 null。
     *
     * 插入 x 除贴边钳制外还要**避让邻居**（≥[MIN_DX]）：只做贴边钳制时，用户在已有控制点
     * 纵向远处（超出命中半径、走不到 hitTest）横向落在边界上的点按会插出 x 重复的点——
     * 零宽线段在采样里造成跳变，且重复点在 [moved] 里 lo>hi 永远"保持原位"、几乎无法选中。
     * 邻居间距不足 2·[MIN_DX] 时无缝可插，同样返回 null（调用方的 null 分支就是"点不动"）。
     */
    fun inserted(points: List<ColorCurve.Point>, x: Float, y: Float): Pair<List<ColorCurve.Point>, Int>? {
        if (points.size >= MAX_POINTS) return null
        val at = points.indexOfFirst { it.x > x }.let { if (it < 0) points.size else it }
        val lo = (if (at > 0) points[at - 1].x + MIN_DX else MIN_DX).coerceAtLeast(MIN_DX)
        val hi = (if (at < points.size) points[at].x - MIN_DX else 1f - MIN_DX).coerceAtMost(1f - MIN_DX)
        if (lo > hi) return null
        val v = x.coerceIn(lo, hi)
        val next = points.toMutableList().also { it.add(at, ColorCurve.Point(v, y.coerceIn(0f, 1f))) }
        return next to at
    }

    /** 删除控制点；端点不可删 */
    fun removed(points: List<ColorCurve.Point>, index: Int): List<ColorCurve.Point> {
        if (index <= 0 || index >= points.size - 1) return points
        return points.toMutableList().also { it.removeAt(index) }
    }
}
