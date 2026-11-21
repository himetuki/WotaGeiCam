package com.wotagei.cam.bt

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * Y47 行为桥：只替换 Android 蓝牙/广播/Audio 边界，[BtSpeakerController] 执行生产字节码。
 *
 * 场景：代理连上 → 系统服务断开 → 控制器必须发布断开态（active=null、设备 connected=false），
 * 且 refresh 能重新走 getProfileProxy 把代理要回来。假服务可切换「服务是否可用」，
 * 用来证明断连后申请位已复位、refresh 是唯一重试入口。
 *
 * 这不是真机蓝牙验证：假适配器/代理只模拟公开 API 的返回与回调时序。
 */
internal class BtBridgeFixture(classes: File, parent: ClassLoader) : Closeable {
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                name.startsWith("android.") -> findClass(name)
                name == "androidx.core.content.ContextCompat" -> findClass(name)
                name == "com.wotagei.cam.diag.Diag" -> findClass(name)
                REAL.any { name == it || name.startsWith("$it\$") } -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少真实生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
    private val probe = loader.loadClass("probe.BtBridgeProbe")

    val controller: Any = loader.loadClass("com.wotagei.cam.bt.BtSpeakerController")
        .getConstructor(loader.loadClass("android.content.Context"))
        .newInstance(loader.loadClass("android.content.Context").getConstructor().newInstance())

    fun setServiceUp(up: Boolean) {
        probe.getMethod("setServiceUp", Boolean::class.javaPrimitiveType).invoke(null, up)
    }

    /** 模拟系统回收/杀掉蓝牙服务：服务回调 onServiceDisconnected(A2DP)。 */
    fun disconnectService() {
        probe.getMethod("disconnect").invoke(null)
    }

    val proxyRequests: Int get() = probe.getField("proxyRequests").get(null) as Int

    @Suppress("UNCHECKED_CAST")
    fun devices(): List<BtDevice> =
        (controller.javaClass.getMethod("getDevices").invoke(controller)
            as kotlinx.coroutines.flow.StateFlow<*>).value as List<BtDevice>

    fun active(): BtDevice? =
        (controller.javaClass.getMethod("getActive").invoke(controller)
            as kotlinx.coroutines.flow.StateFlow<*>).value as BtDevice?

    fun refresh() = controller.javaClass.getMethod("refresh").invoke(controller)

    override fun close() {
        runCatching { controller.javaClass.getMethod("close").invoke(controller) }
        loader.close()
    }

    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    companion object {
        private val REAL = setOf(
            "com.wotagei.cam.bt.BtSpeakerController",
            "com.wotagei.cam.bt.BtSpeakerControllerKt"
        )

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val sources = SOURCES.map { (name, body) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText(body.trimIndent())
                }
            }
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
                ?: error("Y47 边界需要已有宿主 JDK；缺失编译器不是有效行为 RED")
            val output = ByteArrayOutputStream()
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) +
                sources.map { it.absolutePath }
            val exit = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
                .invoke(compiler, null, output, output, options.toTypedArray()) as Int
            check(exit == 0) { "Y47 边界编译失败不是有效行为 RED：\n${output.toString("UTF-8")}" }
            return classes
        }

        private val SOURCES: Map<String, String> = mapOf(
            "probe.BtBridgeProbe" to """
                package probe;
                import android.bluetooth.*;
                import android.content.*;
                import android.media.AudioManager;
                import android.location.LocationManager;
                import java.util.*;

                public class BtBridgeProbe {
                    public static final BluetoothDevice speaker = new BluetoothDevice("AA:BB:CC:DD:EE:FF", "练习音箱", 4);
                    public static final BluetoothAdapter adapter = new BluetoothAdapter();
                    public static final BluetoothA2dp a2dp = new BluetoothA2dp();
                    public static final Map<String,Object> services = new HashMap<>();
                    public static BluetoothProfile.ServiceListener listener;
                    public static boolean serviceUp = true;
                    public static int proxyRequests = 0;
                    static {
                        services.put(Context.AUDIO_SERVICE, new AudioManager());
                        services.put(Context.BLUETOOTH_SERVICE, new BluetoothManager(adapter));
                        services.put(Context.LOCATION_SERVICE, new LocationManager());
                    }
                    public static void setServiceUp(boolean up) { serviceUp = up; }
                    public static void disconnect() {
                        if (listener != null) listener.onServiceDisconnected(BluetoothProfile.A2DP);
                    }
                }
            """,
            "android.Manifest" to """
                package android;
                public class Manifest {
                    public static class permission {
                        public static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
                        public static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
                        public static final String ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
                    }
                }
            """,
            "android.os.Build" to """
                package android.os;
                public class Build {
                    public static class VERSION { public static final int SDK_INT = 35; }
                    public static class VERSION_CODES { public static final int S = 31; public static final int TIRAMISU = 33; }
                }
            """,
            "android.os.SystemClock" to """
                package android.os;
                public class SystemClock { public static long uptimeMillis() { return 1_000L; } }
            """,
            "android.content.pm.PackageManager" to """
                package android.content.pm;
                public class PackageManager { public static final int PERMISSION_GRANTED = 0; }
            """,
            "android.content.Context" to """
                package android.content;
                import probe.BtBridgeProbe;
                public class Context {
                    public static final String AUDIO_SERVICE = "audio";
                    public static final String BLUETOOTH_SERVICE = "bluetooth";
                    public static final String LOCATION_SERVICE = "location";
                    public Context getApplicationContext() { return this; }
                    public Object getSystemService(String name) { return BtBridgeProbe.services.get(name); }
                    public int checkSelfPermission(String permission) { return 0; }
                    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
                    public void unregisterReceiver(BroadcastReceiver receiver) {}
                }
            """,
            "android.content.BroadcastReceiver" to """
                package android.content;
                public abstract class BroadcastReceiver {
                    public BroadcastReceiver() {}
                    public abstract void onReceive(Context context, Intent intent);
                }
            """,
            "android.content.IntentFilter" to """
                package android.content;
                public class IntentFilter {
                    public final void addAction(String action) {}
                }
            """,
            "android.content.Intent" to """
                package android.content;
                import android.bluetooth.BluetoothDevice;
                public class Intent {
                    public String getAction() { return null; }
                    public BluetoothDevice getParcelableExtra(String name) { return null; }
                    public <T> T getParcelableExtra(String name, Class<T> type) { return null; }
                }
            """,
            "androidx.core.content.ContextCompat" to """
                package androidx.core.content;
                import android.content.*;
                public class ContextCompat {
                    public static final int RECEIVER_NOT_EXPORTED = 4;
                    public static int checkSelfPermission(Context context, String permission) {
                        return context.checkSelfPermission(permission);
                    }
                    public static Intent registerReceiver(Context context, BroadcastReceiver receiver,
                                                          IntentFilter filter, int flags) {
                        return context.registerReceiver(receiver, filter);
                    }
                }
            """,
            "android.bluetooth.BluetoothProfile" to """
                package android.bluetooth;
                import java.util.List;
                public interface BluetoothProfile {
                    int A2DP = 2;
                    int STATE_DISCONNECTED = 0;
                    int STATE_CONNECTED = 2;
                    List<BluetoothDevice> getConnectedDevices();
                    int getConnectionState(BluetoothDevice device);
                    interface ServiceListener {
                        void onServiceConnected(int profile, BluetoothProfile proxy);
                        void onServiceDisconnected(int profile);
                    }
                }
            """,
            "android.bluetooth.BluetoothA2dp" to """
                package android.bluetooth;
                import probe.BtBridgeProbe;
                import java.util.*;
                public class BluetoothA2dp implements BluetoothProfile {
                    public static final String ACTION_CONNECTION_STATE_CHANGED =
                        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
                    public List<BluetoothDevice> getConnectedDevices() {
                        return BtBridgeProbe.serviceUp
                            ? Collections.singletonList(BtBridgeProbe.speaker)
                            : Collections.<BluetoothDevice>emptyList();
                    }
                    public int getConnectionState(BluetoothDevice device) {
                        return BtBridgeProbe.serviceUp && device == BtBridgeProbe.speaker
                            ? STATE_CONNECTED : STATE_DISCONNECTED;
                    }
                    public boolean isA2dpPlaying(BluetoothDevice device) { return true; }
                }
            """,
            "android.bluetooth.BluetoothManager" to """
                package android.bluetooth;
                public class BluetoothManager {
                    private final BluetoothAdapter adapter;
                    public BluetoothManager(BluetoothAdapter adapter) { this.adapter = adapter; }
                    public BluetoothAdapter getAdapter() { return adapter; }
                }
            """,
            "android.bluetooth.BluetoothAdapter" to """
                package android.bluetooth;
                import probe.BtBridgeProbe;
                import android.content.Context;
                import java.util.*;
                public class BluetoothAdapter {
                    public static final String ACTION_DISCOVERY_STARTED =
                        "android.bluetooth.adapter.action.DISCOVERY_STARTED";
                    public static final String ACTION_DISCOVERY_FINISHED =
                        "android.bluetooth.adapter.action.DISCOVERY_FINISHED";
                    public static final String ACTION_STATE_CHANGED =
                        "android.bluetooth.adapter.action.STATE_CHANGED";
                    public boolean isEnabled() { return true; }
                    public Set<BluetoothDevice> getBondedDevices() {
                        return new LinkedHashSet<>(Collections.singletonList(BtBridgeProbe.speaker));
                    }
                    public BluetoothDevice getRemoteDevice(String address) { return BtBridgeProbe.speaker; }
                    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener,
                                                   int profile) {
                        BtBridgeProbe.proxyRequests++;
                        BtBridgeProbe.listener = listener;
                        if (!BtBridgeProbe.serviceUp) return false;
                        listener.onServiceConnected(profile, BtBridgeProbe.a2dp);
                        return true;
                    }
                    public void closeProfileProxy(int profile, BluetoothProfile proxy) {}
                    public boolean startDiscovery() { return true; }
                    public boolean cancelDiscovery() { return true; }
                }
            """,
            "android.bluetooth.BluetoothDevice" to """
                package android.bluetooth;
                public class BluetoothDevice {
                    public static final int BOND_BONDED = 12;
                    public static final String ACTION_FOUND = "android.bluetooth.device.action.FOUND";
                    public static final String ACTION_BOND_STATE_CHANGED =
                        "android.bluetooth.device.action.BOND_STATE_CHANGED";
                    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
                    private final String address;
                    private final String name;
                    private final int major;
                    public BluetoothDevice(String address, String name, int major) {
                        this.address = address; this.name = name; this.major = major;
                    }
                    public String getAddress() { return address; }
                    public String getName() { return name; }
                    public int getBondState() { return BOND_BONDED; }
                    public BluetoothClass getBluetoothClass() { return new BluetoothClass(major, 0); }
                }
            """,
            "android.bluetooth.BluetoothClass" to """
                package android.bluetooth;
                public class BluetoothClass {
                    private final int major;
                    private final int device;
                    public BluetoothClass(int major, int device) { this.major = major; this.device = device; }
                    public int getMajorDeviceClass() { return major; }
                    public int getDeviceClass() { return device; }
                    public static class Device {
                        public static class Major {
                            public static final int UNCATEGORIZED = 0;
                            public static final int AUDIO_VIDEO = 4;
                        }
                    }
                }
            """,
            "android.media.AudioManager" to """
                package android.media;
                import android.view.KeyEvent;
                public class AudioManager {
                    public static final int STREAM_MUSIC = 3;
                    public int getStreamMaxVolume(int stream) { return 15; }
                    public int getStreamVolume(int stream) { return 7; }
                    public void setStreamVolume(int stream, int index, int flags) {}
                    public void dispatchMediaKeyEvent(KeyEvent event) {}
                }
            """,
            "android.view.KeyEvent" to """
                package android.view;
                public class KeyEvent {
                    public static final int ACTION_DOWN = 0;
                    public static final int ACTION_UP = 1;
                    public static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;
                    public static final int KEYCODE_MEDIA_NEXT = 87;
                    public static final int KEYCODE_MEDIA_PREVIOUS = 88;
                    public KeyEvent(long downTime, long eventTime, int action, int code, int metaState) {}
                }
            """,
            "android.location.LocationManager" to """
                package android.location;
                public class LocationManager { public boolean isLocationEnabled() { return true; } }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public void i(String tag, String message) {}
                    public void w(String tag, String message) {}
                }
            """
        )
    }
}
