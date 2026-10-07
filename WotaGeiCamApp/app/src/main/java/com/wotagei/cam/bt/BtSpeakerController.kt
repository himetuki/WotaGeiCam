package com.wotagei.cam.bt

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * 一只蓝牙设备在 UI 里的快照（不可变，方便 Compose 比较）。
 *
 * @param connected A2DP 通道是否已连上（不是「已配对」）
 * @param isSpeaker 主类别是音视频设备，或已走 A2DP 连接 —— 弹窗据此把音箱排在手机/手表之前
 * @param bonded 是否已配对：弹窗用它分「已配对 / 附近设备」两组
 */
data class BtDevice(
    val name: String,
    val address: String,
    val deviceClass: Int,
    val connected: Boolean,
    val isSpeaker: Boolean,
    val bonded: Boolean = false
)

/**
 * 蓝牙音箱控制（需求五；07 文档 §1）。v0.0.1 只做 A2DP 媒体音箱这一层。
 *
 * 能力与实现边界：
 * - 连接态：`BluetoothAdapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP)` 拿 [BluetoothA2dp]，
 *   用 `connectedDevices` / `getConnectionState(device)` 判定（`isConnected` 不在公开 SDK 里，勿用）。
 * - 主动连接：`BluetoothA2dp#connect` 是平台隐藏方法，第三方应用无法调用，也不做自动配对，
 *   因此 [connect] 一律返回 false 并置 [openSettings]，由 UI 跳 `Settings.ACTION_BLUETOOTH_SETTINGS`。
 * - 音量：`AudioManager` 的 `STREAM_MUSIC`（系统整机媒体音量，不需要蓝牙权限）。
 * - 播控：`AudioManager.dispatchMediaKeyEvent` 发按键事件，由当前活动播放会话响应，同样不需要蓝牙权限。
 * - 电量：`BluetoothDevice.getBatteryLevel()` 是平台 @hide 方法（公开桩里没有），只能反射取；
 *   取不到就是 null（未知），UI 自己决定隐藏。
 *
 * 权限：API 31+ 需 `BLUETOOTH_CONNECT`（读写设备名/电量/连接态）与 `BLUETOOTH_SCAN`（扫描）。
 * 缺权限时不抛异常，只列不出东西并置 [hasPermission] = false，UI 据此引导授权（见 `ui/dialog/BtSpeakerSheet.kt`）。
 * 蓝牙未开启时同样置 [openSettings]，[adapterEnabled] 供 UI 显示提示。
 *
 * 用法：`remember { BtSpeakerController(context) }` + `DisposableEffect(controller) { onDispose { controller.close() } }`；
 * 打开弹窗时调一次 [refresh]（同步权限、音量、设备表、电量）。
 */
// lint 的 MissingPermission 只认紧邻的 if，看不出本类的两个守卫：[hasConnectPermission]
//（SDK < S 直接放行）与 [missingPermissions]（startScan 前置 return），而且每个调用点都另包了
// runCatching，权限被撤销时最坏是列不出设备，不会抛 SecurityException
@android.annotation.SuppressLint("MissingPermission")
class BtSpeakerController(context: Context) {

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val adapter: BluetoothAdapter? = runCatching {
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())

    /** 已配对 + 本次扫描到的设备（已按「音箱优先 + 已连接优先」排序） */
    val devices: StateFlow<List<BtDevice>> = _devices

    private val _active = MutableStateFlow<BtDevice?>(null)

    /** 当前输出的 A2DP 设备（正在放音者优先），null = 未连接 */
    val active: StateFlow<BtDevice?> = _active

    private val _mediaVolume = MutableStateFlow(0)

    /** `STREAM_MUSIC` 当前档位 */
    val mediaVolume: StateFlow<Int> = _mediaVolume

    private val _volumePercent = MutableStateFlow(0)

    /** 当前档位百分比，给录制页小控件直接显示，省得每处再算 */
    val volumePercent: StateFlow<Int> = _volumePercent

    private val _batteryLevel = MutableStateFlow<Int?>(null)

    /** [active] 设备的电量（0..100），null = 未知/不支持 */
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    private val _hasPermission = MutableStateFlow(true)

    /** API 31+ 的两个蓝牙运行时权限是否齐；false 时 UI 应引导授权 */
    val hasPermission: StateFlow<Boolean> = _hasPermission

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _openSettings = MutableStateFlow(false)

    /** 一次性信号：需要用户去系统蓝牙设置（无权限以外的原因：蓝牙未开 / 无法程序连接） */
    val openSettings: StateFlow<Boolean> = _openSettings

    private val _adapterEnabled = MutableStateFlow(true)

    /** 系统蓝牙开关状态 */
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled

    /** `STREAM_MUSIC` 的最大档位（机型各异，一律运行时读取） */
    val maxVolume: Int = runCatching {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }.getOrNull()?.coerceAtLeast(0) ?: 0

    private var a2dp: BluetoothA2dp? = null
    private var proxyRequested = false
    private var receiverRegistered = false
    // close() 主线程写、onServiceConnected 在 binder 线程读：无同步就无 happens-before，
    // 理论上可读到过期 false 把晚到代理重新赋值（泄漏复现）。@Volatile 收口
    // （与 GlRenderEngine.segmentRotating 同口径）
    @Volatile
    private var closed = false

    /** 本次扫描到的未配对设备，按地址去重，保持发现顺序 */
    private val discovered = LinkedHashMap<String, BluetoothDevice>()

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.A2DP) return
            // close() 先于绑定回调到达（进录制页立刻退出的毫秒级窗口）时，close 的
            // a2dp==null 短路已经跳过了 closeProfileProxy——晚到的代理必须在这里当场解绑，
            // 否则赋值给 a2dp 后无人再释放，代理与服务绑定驻留到进程死
            if (closed) {
                if (proxy != null) {
                    runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
                        .onFailure { Log.w(TAG, "late proxy closeProfileProxy failed: ${it.message}") }
                }
                return
            }
            a2dp = proxy as? BluetoothA2dp
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _isScanning.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                    rebuild()
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.deviceExtra() ?: return
                    if (!discovered.containsKey(device.address)) discovered[device.address] = device
                    rebuild()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()

                else -> Unit
            }
        }
    }

    init {
        registerReceiver()
        requestProxy()
        refresh()
    }

    // ------------------------------------------------- 状态刷新

    /** 重读权限、蓝牙开关、设备表、当前输出设备、音量与电量 */
    fun refresh() {
        if (closed) return
        _hasPermission.value = missingPermissions().isEmpty()
        _adapterEnabled.value = runCatching { adapter?.isEnabled }.getOrNull() == true
        syncVolume()
        rebuild()
    }

    private fun syncVolume() {
        val manager = audioManager ?: return
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        _mediaVolume.value = index
        _volumePercent.value = if (maxVolume <= 0) 0 else (index * 100f / maxVolume).roundToInt()
    }

    private fun rebuild() {
        if (closed) return
        val connectable = hasConnectPermission()
        val connectedAddresses = linkedSetOf<String>()
        val profile = a2dp
        if (profile != null && connectable) {
            runCatching { profile.connectedDevices }.getOrNull()?.forEach { device ->
                device?.let { connectedAddresses += it.address }
            }
        }
        val entries = ArrayList<BtDevice>()
        val seen = HashSet<String>()
        val bonded = if (connectable) {
            runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        } else emptySet()
        bonded.forEach { device ->
            if (device == null || !seen.add(device.address)) return@forEach
            entries += entryOf(device, connectedAddresses)
        }
        // 只连上但未配对（少见，例如权限被撤销后残留连接）也要出现在列表里
        if (profile != null && connectable) {
            runCatching { profile.connectedDevices }.getOrNull()?.forEach { device ->
                if (device == null || !seen.add(device.address)) return@forEach
                entries += entryOf(device, connectedAddresses)
            }
        }
        discovered.values.forEach { device ->
            if (!seen.add(device.address)) return@forEach
            entries += entryOf(device, connectedAddresses)
        }
        // 排序：音箱在前 → 已连接在前 → 名称在前（无名字时用地址，避免并列导致的顺序抖动）
        val sorted = entries.sortedWith(
            compareByDescending<BtDevice> { it.isSpeaker }
                .thenByDescending { it.connected }
                .thenBy { it.name.ifBlank { it.address } }
        )
        _devices.value = sorted
        val active = pickActive(sorted, connectedAddresses)
        _active.value = active
        _batteryLevel.value = readBattery(active?.address)
    }

    private fun entryOf(device: BluetoothDevice, connectedAddresses: Set<String>): BtDevice {
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        val major = runCatching { btClass?.majorDeviceClass }.getOrNull()
            ?: BluetoothClass.Device.Major.UNCATEGORIZED
        val connected = connectedAddresses.contains(device.address) ||
            connectionState(device) == BluetoothProfile.STATE_CONNECTED
        return BtDevice(
            name = if (hasConnectPermission()) runCatching { device.name }.getOrNull().orEmpty() else "",
            address = device.address,
            deviceClass = runCatching { btClass?.deviceClass }.getOrNull() ?: 0,
            connected = connected,
            isSpeaker = major == BluetoothClass.Device.Major.AUDIO_VIDEO || connected,
            bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        )
    }

    private fun pickActive(entries: List<BtDevice>, connectedAddresses: Set<String>): BtDevice? {
        val connected = entries.firstOrNull { it.connected } ?: return null
        if (connectedAddresses.isEmpty()) return connected
        val profile = a2dp ?: return connected
        // 正在放音的那只才是「当前输出」，双连接（手机 + 音箱）时区分得开
        val playing = runCatching {
            profile.connectedDevices.firstOrNull { device ->
                device != null && runCatching { profile.isA2dpPlaying(device) }.getOrDefault(false)
            }
        }.getOrNull()
        return playing?.let { target -> entries.firstOrNull { it.address == target.address } } ?: connected
    }

    private fun connectionState(device: BluetoothDevice): Int {
        val profile = a2dp ?: return BluetoothProfile.STATE_DISCONNECTED
        if (!hasConnectPermission()) return BluetoothProfile.STATE_DISCONNECTED
        return runCatching { profile.getConnectionState(device) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
    }

    /** getBatteryLevel 是平台隐藏方法（compileSdk 的 android.jar 里没有），只能反射；拿不到即未知 */
    private fun readBattery(address: String?): Int? {
        if (address.isNullOrEmpty() || !hasConnectPermission()) return null
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return null
        return try {
            val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            val level = (method.invoke(device) as? Int) ?: BATTERY_UNKNOWN
            level.takeIf { it >= 0 }
        } catch (t: Throwable) {
            Log.w(TAG, "getBatteryLevel unavailable: ${t.message}")
            null
        }
    }

    // ------------------------------------------------- 扫描

    /** 开始系统级设备发现（约 12s 后系统自动结束）；缺权限或蓝牙未开时只更新状态流 */
    fun startScan() {
        val bluetooth = adapter ?: return
        refresh()
        if (!bluetooth.isEnabled) {
            _openSettings.value = true
            return
        }
        if (missingPermissions().isNotEmpty()) {
            Log.w(TAG, "startScan skipped: missing ${missingPermissions().joinToString()}")
            return
        }
        if (runCatching { bluetooth.isDiscovering }.getOrDefault(false)) return
        val started = runCatching { bluetooth.startDiscovery() }.getOrDefault(false)
        _isScanning.value = started
        if (!started) Log.w(TAG, "startDiscovery returned false")
    }

    /** 提前结束发现；列表里已发现的结果保留 */
    fun stopScan() {
        runCatching { adapter?.takeIf { it.isDiscovering }?.cancelDiscovery() }
            .onFailure { Log.w(TAG, "cancelDiscovery failed: ${it.message}") }
        _isScanning.value = false
    }

    /** 清空「附近设备」缓存（弹窗关闭时调用，避免上次结果一直挂着） */
    fun clearDiscovered() {
        discovered.clear()
        rebuild()
    }

    // ------------------------------------------------- 连接与播放控制

    /**
     * 「连接」在 v0.0.1 无法程序化完成：A2DP 的 connect 不是公开 API，且按约定不做自动配对。
     *
     * @return 恒为 false；同时置 [openSettings]，UI 收到后跳 `Settings.ACTION_BLUETOOTH_SETTINGS`
     */
    fun connect(device: BtDevice): Boolean {
        _openSettings.value = true
        Log.w(TAG, "connect ${device.address} delegated to system bluetooth settings")
        return false
    }

    /** UI 消费掉 [openSettings] 信号后调用，避免下次重组重复拉起设置页 */
    fun consumeSettingsRequest() {
        _openSettings.value = false
    }

    /** 设媒体音量（自动截断到 0..[maxVolume]）；不弹系统音量条，值由弹窗滑杆自己显示 */
    fun setVolume(value: Int) {
        val manager = audioManager ?: return
        val target = value.coerceIn(0, maxVolume)
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { Log.w(TAG, "setStreamVolume failed: ${it.message}") }
        syncVolume()
    }

    fun playPause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun prev() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun sendMediaKey(keyCode: Int) {
        val manager = audioManager ?: return
        val now = SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { Log.w(TAG, "dispatchMediaKeyEvent($keyCode) failed: ${it.message}") }
    }

    // ------------------------------------------------- 权限与生命周期

    /** 需要用户授予的蓝牙权限（API 30 及以下走清单里的 install 期权限，恒为空） */
    fun missingPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return buildList {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
        }.toTypedArray()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestProxy() {
        val bluetooth = adapter ?: return
        if (proxyRequested) return
        proxyRequested = runCatching {
            bluetooth.getProfileProxy(app, serviceListener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!proxyRequested) Log.w(TAG, "getProfileProxy(A2DP) failed")
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // 全是受保护的system 广播，NOT_EXPORTED 即可（targetSdk 34 必须显式给导出标志）
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
        if (!receiverRegistered) Log.w(TAG, "registerReceiver(bluetooth) failed")
    }

    /** 释放代理与广播接收器；调用后本实例不再更新（Compose onDispose 里调） */
    fun close() {
        if (closed) return
        closed = true
        stopScan()
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "unregisterReceiver failed: ${it.message}") }
            receiverRegistered = false
        }
        val bluetooth = adapter
        val profile = a2dp
        if (bluetooth != null && profile != null) {
            runCatching { bluetooth.closeProfileProxy(BluetoothProfile.A2DP, profile) }
                .onFailure { Log.w(TAG, "closeProfileProxy failed: ${it.message}") }
        }
        a2dp = null
        proxyRequested = false
    }

    private companion object {
        const val TAG = "WotaBt"

        /** 平台约定：-1 = 该设备没上报电量 */
        const val BATTERY_UNKNOWN = -1
    }
}

/** 扫描结果里的设备字段（API 33 起 getParcelableExtra 有类型化重载，旧版仍是 deprecated 重载） */
@Suppress("DEPRECATION")
private fun Intent.deviceExtra(): BluetoothDevice? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
    }
} catch (t: Throwable) {
    Log.w("WotaBt", "ACTION_FOUND payload unreadable: ${t.message}")
    null
}
