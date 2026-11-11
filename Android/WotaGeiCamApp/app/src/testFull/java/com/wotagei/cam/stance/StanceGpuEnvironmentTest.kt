package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * 执行 full 的真实诊断方法：旧平台桩故意不声明 SOC 字段，不能用新版 android.jar 假装旧系统。
 * 只隔离平台边界和日志，生产方法/版本判断不复制；不启动模型、分析线程或原生推理。
 * 这是 JVM 字段链接与分支回归，Android 10/11 的实际启动仍需设备验证。
 */
class StanceGpuEnvironmentTest {

    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/stance-gpu-environment-test").apply { mkdirs() })

    @Test
    fun `API29缺少SOC字段仍输出硬件与Vulkan诊断`() {
        assertEquals("GPU 环境 HW=test-hardware vulkan=true vulkanCompute=false", dumpOnPlatform(29))
    }

    @Test
    fun `API30缺少SOC字段仍输出硬件与Vulkan诊断`() {
        assertEquals("GPU 环境 HW=test-hardware vulkan=true vulkanCompute=false", dumpOnPlatform(30))
    }

    @Test
    fun `API31保留SOC型号和厂商诊断`() {
        assertEquals(
            "GPU 环境 SOC_MODEL=test-soc SOC_MFR=test-maker HW=test-hardware vulkan=true vulkanCompute=false",
            dumpOnPlatform(31)
        )
    }

    @Test
    fun `新系统SOC为空时保留问号兜底`() {
        assertEquals(
            "GPU 环境 SOC_MODEL=? SOC_MFR=? HW=test-hardware vulkan=true vulkanCompute=false",
            dumpOnPlatform(34, emptySoc = true)
        )
    }

    private fun dumpOnPlatform(sdk: Int, emptySoc: Boolean = false): String {
        val root = temporary.newFolder("api-$sdk")
        val classes = File(root, "classes").apply { mkdirs() }
        val socFields = if (sdk >= 31) {
            val model = if (emptySoc) "" else "test-soc"
            val maker = if (emptySoc) "" else "test-maker"
            "public static String SOC_MODEL = \"$model\"; public static String SOC_MANUFACTURER = \"$maker\";"
        } else ""
        val sources = mapOf(
            "android/os/Build.java" to """
                package android.os;
                public class Build {
                    public static String HARDWARE = "test-hardware";
                    $socFields
                    public static class VERSION { public static int SDK_INT = $sdk; }
                }
            """.trimIndent(),
            "android/content/Context.java" to """
                package android.content;
                public class Context {
                    public android.content.pm.PackageManager getPackageManager() {
                        return new android.content.pm.PackageManager();
                    }
                }
            """.trimIndent(),
            "android/content/pm/PackageManager.java" to """
                package android.content.pm;
                public class PackageManager {
                    public boolean hasSystemFeature(String feature) {
                        return "android.hardware.vulkan.version".equals(feature);
                    }
                }
            """.trimIndent(),
            "com/wotagei/cam/diag/Diag.java" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public static String lastMessage;
                    public void i(String tag, String message) { lastMessage = message; }
                }
            """.trimIndent()
        )
        val files = sources.map { (path, source) ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(source)
            }
        }
        // Android 测试编译的 bootclasspath 不含 javax.tools，运行时才反射使用宿主 JDK。
        val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
        assertNotNull("回归需要工程指定的 JDK，不下载额外测试依赖", compiler)
        val options = listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath)
        val compile = Class.forName("javax.tools.Tool").getMethod(
            "run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java
        )
        assertEquals("平台边界桩必须成功编译", 0, compile.invoke(compiler, null, null, null, (options + files.map { it.absolutePath }).toTypedArray()))

        DiagnosticLoader(classes, javaClass.classLoader).use { loader ->
            val build = loader.loadClass("android.os.Build")
            if (sdk < 31) {
                assertThrows(NoSuchFieldException::class.java) { build.getDeclaredField("SOC_MODEL") }
                assertThrows(NoSuchFieldException::class.java) { build.getDeclaredField("SOC_MANUFACTURER") }
            }
            val controllerClass = loader.loadClass(CONTROLLER)
            // 不跑控制器构造：其余字段/模型/线程与本诊断无关，只注入方法实际需要的 Context。
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafeField.get(null), controllerClass)
            val context = loader.loadClass("android.content.Context").getDeclaredConstructor().newInstance()
            controllerClass.getDeclaredField("appContext").apply { isAccessible = true }.set(controller, context)
            try {
                controllerClass.getDeclaredMethod("dumpGpuEnvironment").apply { isAccessible = true }.invoke(controller)
            } catch (e: InvocationTargetException) {
                throw AssertionError("API $sdk 的生产诊断不应访问缺失字段或崩溃", e.targetException)
            }
            val message = loader.loadClass("com.wotagei.cam.diag.Diag").getField("lastMessage").get(null) as String?
            assertNotNull("必须真正输出诊断，不能靠 catch 吞掉错误后假绿", message)
            return message!!
        }
    }

    private class DiagnosticLoader(classes: File, parent: ClassLoader) :
        URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                name == CONTROLLER || name.startsWith("$CONTROLLER\$") -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少实际生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                name == "android.os.Build" || name.startsWith("android.os.Build\$") ||
                    name == "android.content.Context" || name == "android.content.pm.PackageManager" ||
                    name == "com.wotagei.cam.diag.Diag" -> findClass(name)
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }

    companion object {
        private const val CONTROLLER = "com.wotagei.cam.stance.StanceController"
    }
}
