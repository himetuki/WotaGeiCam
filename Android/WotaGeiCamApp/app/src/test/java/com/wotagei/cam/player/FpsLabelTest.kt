package com.wotagei.cam.player

import com.wotagei.cam.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 帧率读数的取串规则（#51）。
 *
 * 起因：用 ffmpeg 造了一条 r_frame_rate 与 avg_frame_rate 都严格等于 30000/1001 的成片，
 * 播放器却显示「30.0 fps」—— 串里写的是 `%1$.1f`，一位小数把非整数帧率四舍五入掉了。
 * 这条读数同时是「整片循环/帧步进」时用户判断素材真实帧率的唯一入口，不能藏。
 *
 * 只测取串，不测格式化本身：`stepFrame` 用的是 `PlayerEngine` 里的原始 float，
 * 数学没被取整污染（已用「后退 60 帧 = 2.00s」在真机确认量级）。
 */
class FpsLabelTest {

    @Test
    fun `整数帧率仍走一位小数`() {
        assertEquals(R.string.player_fps_step, fpsStepLabelRes(25f))
        assertEquals(R.string.player_fps_step, fpsStepLabelRes(30f))
        assertEquals(R.string.player_fps_step, fpsStepLabelRes(24f))
    }

    @Test
    fun `非整数帧率必须走两位小数`() {
        assertEquals(R.string.player_fps_step_exact, fpsStepLabelRes(30000f / 1001f))  // 29.970001
        assertEquals(R.string.player_fps_step_exact, fpsStepLabelRes(24.75f))
        assertEquals(R.string.player_fps_step_exact, fpsStepLabelRes(99f / 4f))
    }

    @Test
    fun `阈值边界不把整数误判成非整数`() {
        // 浮点表示误差要走一位小数，否则 25.000002 这种会显示成 25.00 反而更怪
        assertEquals(R.string.player_fps_step, fpsStepLabelRes(25.000002f))
        // 真实的小数帧率不能被这个容差吞掉
        assertEquals(R.string.player_fps_step_exact, fpsStepLabelRes(29.99f))
    }
}
