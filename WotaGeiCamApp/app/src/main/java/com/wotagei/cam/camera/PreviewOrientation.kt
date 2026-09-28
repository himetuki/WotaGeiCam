package com.wotagei.cam.camera

/**
 * 预览方向状态机：传感器方向、显示旋转、缓冲变换三路人各自写入，需要的角度在内部现算。
 *
 * 为什么分三路：
 * - `Camera2Engine` 每次开/重开会话都会写裸 `sensorOrientation`，若残余角由 UI 另路一次性覆盖，
 *   任何一次重开（停止录制、错误恢复、换镜头）都会把它覆写回 90°（真机「GPU 预览顺时针 90°」的第一层根因）。
 * - 显示旋转角只在 UI 侧知道，且转屏必须立刻生效，所以单独一路喂进来。
 * - **缓冲变换**（`SurfaceTexture.getTransformMatrix`）是第三方：同一份相机帧，不同 HAL 给的矩阵
 *   可能自带 90/180/270 旋转（真机 WIKO GAR-AN60 给的是「转置型」矩阵，等价于再顺时针转 90°）。
 *   这一路只能运行时读，读不到就当 0（等价于教科书约定），所以普通机型行为完全不变。
 */
class PreviewOrientation {

    private var sensorDegrees = 0
    private var displayDegrees = 0

    /** 前置镜头取 true；镜像只作用在屏幕空间，上屏与录出因此方向一致 */
    var mirrored: Boolean = false
        private set

    /**
     * 缓冲变换矩阵自带的顺时针旋转分量（0/90/180/270）。
     * 由 [setStMatrix] 从运行时矩阵解析；未解析到（矩阵还没到 / 不是八种标准朝向之一）取 0。
     */
    var stRotationDegrees: Int = 0
        private set

    /** 由相机引擎写入：`CameraCharacteristics.SENSOR_ORIENTATION` 原值 */
    fun setSensorOrientation(sensorOrientation: Int, mirrored: Boolean) {
        sensorDegrees = normalize(sensorOrientation)
        this.mirrored = mirrored
    }


    /** 由 UI 写入：当前显示旋转角（0/90/180/270） */
    fun setDisplayDegrees(degrees: Int) {
        displayDegrees = normalize(degrees)
    }

    /**
     * 由渲染侧写入 `SurfaceTexture.getTransformMatrix` 的 16 元素列主序矩阵。
     * @return 解析出的旋转分量发生变化时返回 true（调用方据此催一次重绘）
     */
    fun setStMatrix(matrix: FloatArray): Boolean {
        val next = stRotationFromMatrix(matrix)
        if (next < 0 || next == stRotationDegrees) return false
        stRotationDegrees = next
        return true
    }

    /**
     * 内容相对**已归一化缓冲**需顺时针旋转的度数 = 传感器方向 − 显示旋转。
     * 用于等比居中包围盒、预览宽高比、录像容器的 `orientationHint`：这些都是「画面该转正多少」的语义，
     * 与 HAL 给的缓冲变换无关。
     */
    fun residualDegrees(): Int = normalize(sensorDegrees - displayDegrees)

    /**
     * 渲染侧真正要施加的度数：先把矩阵自带的旋转分量抵消掉，再按 [residualDegrees] 转正。
     * GL 用在纹理坐标上、DIRECT 用在 `TextureView.setTransform` 的视图空间上，两处同值，
     * 所以两种渲染模式的方向逐度一致。
     */
    fun appliedDegrees(): Int = normalize(residualDegrees() + stRotationDegrees)

    /** 施加 [appliedDegrees] 之前，输入源（视图/纹理里那张已归一化的图）的宽高是否被矩阵换了轴 */
    fun stSwapsSource(): Boolean = stRotationDegrees % 180 != 0

    companion object {
        fun normalize(deg: Int): Int = ((deg % 360) + 360) % 360

        /**
         * 从列主序 4x4 的缓冲变换矩阵里解析「矩阵自带的顺时针旋转分量」。
         *
         * 判据不靠猜：把 4 个候选角 k 逐个代进「纹理坐标变换」，看哪个 k 能让
         * `矩阵 ∘ 旋转(k)` 恰好等于**教科书约定**的归一化矩阵 `(s,t) → (s, 1-t)`
         * （即只把 GL 的 t 轴翻回图像的行序，不含任何旋转）。命中的 k 就是矩阵多转的度数。
         * 矩阵还没取到（全 0）返回 **-1（未知）**，让调用方保留上一次的结果；解析不出八种标准朝向时返回 0，
         * 行为退化成教科书约定。
         */
        fun stRotationFromMatrix(matrix: FloatArray): Int {
            if (matrix.size < 16) return -1
            // 列主序：元素 (row, col) = matrix[col * 4 + row]；仿射部分只看前两行前两列 + 平移
            val a11 = matrix[0]; val a12 = matrix[4]; val b1 = matrix[12]
            val a21 = matrix[1]; val a22 = matrix[5]; val b2 = matrix[13]
            if (allZero(a11, a12, a21, a22, b1, b2)) return -1
            for (k in intArrayOf(0, 90, 180, 270)) {
                val rot = rotationMap(k)
                // 复合 (s,t) -> matrix(rot(s,t))，与归一化矩阵逐点比对
                if (matchesCanonical(a11, a12, b1, a21, a22, b2, rot)) return k
            }
            return 0
        }

        /** 归一化矩阵 `(s,t) → (s, 1-t)` 的仿射系数 */
        private const val CANONICAL_EPS = 1e-3f

        private fun allZero(vararg v: Float): Boolean = v.all { kotlin.math.abs(it) < CANONICAL_EPS }

        /**
         * 纹理坐标四边形空间里的顺时针旋转 k（与 `GlRenderEngine.buildTexCoords` 同一条推导）：
         * 0:(s,t) 90:(1-t,s) 180:(1-s,1-t) 270:(t,1-s)，写成 (系数, 平移) 便于复合。
         */
        private fun rotationMap(degrees: Int): FloatArray = when (normalize(degrees)) {
            90 -> floatArrayOf(0f, -1f, 1f, 1f, 0f, 0f)
            180 -> floatArrayOf(-1f, 0f, 1f, 0f, -1f, 1f)
            270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }

        /** 复合矩阵 `M ∘ Rot` 是否等于 `(s,t) → (s, 1-t)` */
        private fun matchesCanonical(
            a11: Float, a12: Float, b1: Float,
            a21: Float, a22: Float, b2: Float,
            rot: FloatArray
        ): Boolean {
            // rot 的第 0 行 (r00,r01,r02) 与第 1 行 (r10,r11,r12)
            val c11 = a11 * rot[0] + a12 * rot[3]
            val c12 = a11 * rot[1] + a12 * rot[4]
            val cb1 = a11 * rot[2] + a12 * rot[5] + b1
            val c21 = a21 * rot[0] + a22 * rot[3]
            val c22 = a21 * rot[1] + a22 * rot[4]
            val cb2 = a21 * rot[2] + a22 * rot[5] + b2
            return near(c11, 1f) && near(c12, 0f) && near(cb1, 0f) &&
                near(c21, 0f) && near(c22, -1f) && near(cb2, 1f)
        }

        private fun near(actual: Float, expected: Float): Boolean =
            kotlin.math.abs(actual - expected) < 1e-2f
    }
}
