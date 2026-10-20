package com.wotagei.cam.bt

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 10/11 蓝牙扫描「另一半门槛」的接线守卫（2026-10-20）。
 *
 * 平台对 discovery 结果的过滤是「位置权限已授 **且** 系统定位服务开着」两件事同时成立。
 * 权限侧早有 missingPermissions 早退 + 面板授权引导；定位开关侧此前完全没有出口——权限授了、
 * 开关没开时 startDiscovery 照样报成功，用户只看到「附近设备」永远空着。本守卫钉住三处接线：
 * ① startScan 发起前先查开关（S 以下）；② refresh 把开关状态同步进 locationServiceOff
 * （用户开完开关回来，引导要能自己熄灭）；③ 面板对"开关没开"亮 HintCard + 去系统设置的出口。
 */
class BtLocationServiceGuardTest {

    @Test
    fun `startScan 必须在发起前挡住定位开关未开`() {
        val f = codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        val startScan = f.substringAfter("fun startScan(").substringBefore("fun stopScan(")
        val gate = startScan.indexOf("locationServiceDisabled()")
        val discovery = startScan.indexOf("startDiscovery()")
        assertTrue(
            "S 以下必须在 startDiscovery 前查定位开关（否则扫描发起成功却静默恒空）",
            gate >= 0 && discovery > gate
        )
        assertTrue("挡下时必须置位引导状态", startScan.contains("_locationServiceOff.value = true"))
    }

    @Test
    fun `refresh 必须同步定位开关状态`() {
        val f = codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        val refresh = f.substringAfter("fun refresh(").substringBefore("\n    fun ")
        assertTrue(
            "refresh 必须重算 locationServiceOff（用户开完开关回来，引导要能自己熄灭）",
            refresh.contains("_locationServiceOff.value =") && refresh.contains("locationServiceDisabled()")
        )
    }

    @Test
    fun `面板必须有开定位引导与系统设置出口`() {
        val f = codeOnly(mainSourceText("ui/dialog/BtSpeakerPanel.kt"))
        assertTrue("面板必须消费 locationServiceOff", f.contains("locationServiceOff"))
        assertTrue(
            "必须跳系统定位设置页（ACTION_LOCATION_SOURCE_SETTINGS）",
            f.contains("ACTION_LOCATION_SOURCE_SETTINGS")
        )
    }
}
