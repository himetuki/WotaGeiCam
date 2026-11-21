package com.wotagei.cam.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Y47 行为桥：A2DP 服务代理断开后，页面必须立刻看到断开态，且 refresh 能把代理重新要回来。
 *
 * 旧缺陷只清 `a2dp` 不清 `proxyRequested`：断连后 UI 抱着旧快照一直显示"已连接"，
 * 且 `requestProxy` 被申请位短路，代理再也不会回来。这里用假适配器/代理驱动生产
 * [BtSpeakerController] 的真实回调路径（非真机蓝牙验证）。
 */
class BtDisconnectBridgeTest {

    @Test
    fun `服务断连后发布断开态且 refresh 重新申请代理`() = platform { p ->
        assertEquals("init 必须申请一次代理", 1, p.proxyRequests)
        val before = p.devices()
        assertEquals(1, before.size)
        assertTrue("初始代理连上时设备必须呈已连接", before.single().connected)
        assertNotNull("初始必须有当前输出设备", p.active())

        // 系统回收蓝牙服务：先让服务不可用，再回调 onServiceDisconnected。
        p.setServiceUp(false)
        p.disconnectService()

        assertNull("断连后不得继续把旧设备当当前输出", p.active())
        assertFalse("断连后设备不能仍显示已连接", p.devices().single().connected)
        assertEquals("断连后必须从 refresh 单入口重新申请代理", 2, p.proxyRequests)

        // 服务仍不可用：每次显式 refresh 都允许重试（申请位已复位），状态保持断开。
        p.refresh()
        assertEquals("申请位不能永久短路", 3, p.proxyRequests)
        assertNull(p.active())
        assertFalse(p.devices().single().connected)

        // 服务恢复：同一条 refresh 入口把代理/连接态一起重建。
        p.setServiceUp(true)
        p.refresh()
        assertEquals(4, p.proxyRequests)
        assertTrue("代理恢复后设备必须重新显示已连接", p.devices().single().connected)
        assertNotNull("代理恢复后当前输出设备必须回来", p.active())
    }

    private fun platform(block: (BtBridgeFixture) -> Unit) {
        BtBridgeFixture(classes, requireNotNull(javaClass.classLoader!!)).use(block)
    }

    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/bt-y47-bridge-test").apply { mkdirs() })
        private val classes: File by lazy { BtBridgeFixture.compile(temporary.newFolder("bt-boundary")) }
    }
}
