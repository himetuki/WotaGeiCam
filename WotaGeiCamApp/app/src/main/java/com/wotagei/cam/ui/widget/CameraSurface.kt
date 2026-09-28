// 两个预览各建一枚 Surface 并在 onSurfaceTextureDestroyed 里 release（本文件唯一的 Surface 出口），
// lint 跟不住跨回调的局部变量，四条 Recycle 全是误报，压在这里而不是逐处写注释
@file:android.annotation.SuppressLint("Recycle")

package com.wotagei.cam.ui.widget

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.wotagei.cam.camera.DirectSink
import com.wotagei.cam.camera.DisplaySurfaceReceiver
import com.wotagei.cam.camera.GlRenderEngine
import com.wotagei.cam.camera.PreviewSink
import com.wotagei.cam.core.RenderMode

/**
 * 预览承载（03 文档第 1 节双渲染模式）。
 *
 * - [RenderMode.DIRECT]：`TextureView` + [DirectSink] 直显，相机帧不落任何 GL，
 *   方向与信箱由 [DirectSink.newDisplayMatrix] 经 `TextureView.setTransform` 施加；
 *   **不吃任何画面特效**（UI 需把特效按钮置灰）。
 *   为什么不用 `SurfaceView`：窗口面按传感器原始方向出图，应用侧没有变换通道，真机上与 GPU 差 90°。
 * - 其他（GPU）：`TextureView` + [GlRenderEngine] 自建 EGL 线程，
 *   相机帧走 OES 纹理，斑马纹/峰值对焦生效，录出=所见。
 *
 * 窗口面投递（两条路都通，按 sink 的实现能力择一）：
 * - GPU：`sink` 是 [GlRenderEngine]，已实现 [DisplaySurfaceReceiver]，无需额外接线；
 * - DIRECT：`sink` 必须是 [DirectSink]（同样实现 [DisplaySurfaceReceiver]），
 *   面变化后经 [onDisplaySurface] 通知门面重开会话（输出流尺寸可能随之改变）。
 *
 * 尺寸建议：预览上限取 1080p 档（06 文档 §6 降热），且 CameraSurface 应是所在容器的最底层子节点。
 *
 * @param mode 渲染模式（来自 `WotaParams.renderMode`）
 * @param sink 预览汇聚点：GPU 模式传 [GlRenderEngine]，DIRECT 传 [DirectSink]
 * @param modifier 布局修饰；本组件不自行设定宽高
 * @param onDisplaySurface 窗口面回调（面就绪/尺寸变化/销毁时各一次）
 */
@Composable
fun CameraSurface(
    mode: RenderMode,
    sink: PreviewSink,
    modifier: Modifier = Modifier,
    onDisplaySurface: (surface: Surface?, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> }
) {
    // rememberUpdatedState 返回的 State 实例跨重组稳定，视图工厂里捕获它即可始终读到最新 sink
    val newPush: (surface: Surface?, widthPx: Int, heightPx: Int) -> Unit = { surface, widthPx, heightPx ->
        (sink as? DisplaySurfaceReceiver)?.onDisplaySurfaceChanged(surface, widthPx, heightPx)
        onDisplaySurface(surface, widthPx, heightPx)
    }
    val push = rememberUpdatedState(newPush)
    when (mode) {
        // DIRECT 的变换矩阵只认 DirectSink；传错 sink 就不出面，让上层走「预览未就绪」遮罩而不是静默拉伸
        RenderMode.DIRECT -> DirectPreview(sink as? DirectSink, push, modifier)
        else -> GpuPreview(push, modifier)
    }
}

/**
 * DIRECT：`TextureView` 的 SurfaceTexture 直接当相机输出面（零 GL 管线，系统合成器上屏）。
 *
 * 顺序固定：先把面与视图尺寸交给 sink（sink 据此挑预览流尺寸、算矩阵）→ 重设缓冲尺寸并施加矩阵
 * → 再把面投递出去触发建会话。反过来会让会话按旧缓冲尺寸建流（真机表现为按视图比例拉伸失真）。
 *
 * 每帧还要读一次 `SurfaceTexture.getTransformMatrix`：矩阵自带多少旋转是**机型差异**，只能运行时读
 * （首帧之后才有值），读到新的旋转分量就重设一次显示矩阵。
 */
@Composable
private fun DirectPreview(
    direct: DirectSink?,
    push: State<(Surface?, Int, Int) -> Unit>,
    modifier: Modifier
) {
    if (direct == null) return
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                var windowSurface: Surface? = null
                val stMatrix = FloatArray(16)

                fun reconfigure() {
                    val texture = surfaceTexture ?: return
                    val (w, h) = direct.streamSize()
                    if (w > 0 && h > 0) texture.setDefaultBufferSize(w, h)
                    texture.getTransformMatrix(stMatrix)
                    direct.onTextureMatrix(stMatrix)
                    // setTransform 是视图级 API（作用于上屏四角），不是 SurfaceTexture 的
                    this@apply.setTransform(direct.newDisplayMatrix())
                }
                direct.onReconfigure = { reconfigure() }

                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val surface = Surface(texture).also { windowSurface = it }
                        push.value(surface, width, height)
                        reconfigure()
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        val surface = windowSurface ?: Surface(texture).also { windowSurface = it }
                        push.value(surface, width, height)
                        reconfigure()
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        direct.onReconfigure = null
                        push.value(null, 0, 0)
                        windowSurface?.release()
                        windowSurface = null
                        // 返回 true：本视图的 SurfaceTexture 无人复用，交还系统释放
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                        // 矩阵只在换镜头/换流配置时才变，解析出新的旋转分量才重设一次视图变换
                        texture.getTransformMatrix(stMatrix)
                        if (direct.onTextureMatrix(stMatrix)) this@apply.setTransform(direct.newDisplayMatrix())
                    }
                }
            }
        }
    )
}

/**
 * GPU：TextureView 的 SurfaceTexture 当作「窗口」，引擎在其上建 EGLSurface 输出。
 * 相机帧另有引擎持有的 OES SurfaceTexture，两者不共用。
 */
@Composable
private fun GpuPreview(
    push: State<(Surface?, Int, Int) -> Unit>,
    modifier: Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                var windowSurface: Surface? = null
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        // 缓冲尺寸=视图像素，引擎输出 1:1 上屏不拉伸
                        texture.setDefaultBufferSize(width, height)
                        val surface = Surface(texture)
                        windowSurface = surface
                        push.value(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(width, height)
                        // 复用同一 Surface：引擎只重算视口，不重建 EGL 窗口面
                        val surface = windowSurface ?: Surface(texture).also { windowSurface = it }
                        push.value(surface, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        push.value(null, 0, 0)
                        windowSurface?.release()
                        windowSurface = null
                        // 返回 true：本视图的 SurfaceTexture 无人复用，交还系统释放
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        }
    )
}
