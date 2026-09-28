package com.wotagei.cam.camera

import android.view.Surface

/** 预览/录像输出面提供者：DIRECT 由 Camera2 直连，GPU 由 GL 引擎承接 */
interface PreviewSink {
    fun cameraTargets(): List<Surface>      // 挂进 CaptureRequest 的输出面
    fun onSizeChanged(w: Int, h: Int)
    fun setOrientation(sensorOrientation: Int, mirrored: Boolean)

    /**
     * 当前显示旋转角（0/90/180/270），由 UI 单独写入。
     * 与 [setOrientation] 分两路给，sink 内部现算残余角：引擎每次重开会话都会重写裸
     * `sensorOrientation`，若残余角由 UI 一次性覆盖，重开后必被覆写（真机 GPU 预览转 90° 的根因）。
     * 默认空实现 = 该 sink 不需要补显示旋转。
     */
    fun setDisplayDegrees(degrees: Int) {}

    /**
     * 参与相机会话结构签名的令牌：输出面的「形状」（尺寸/流配置）变化时返回新值，
     * 让引擎知道要重建会话。默认空串 = 面尺寸与本会话无关（GL 路径自己管缓冲尺寸）。
     */
    fun structuralToken(): String = ""
}
