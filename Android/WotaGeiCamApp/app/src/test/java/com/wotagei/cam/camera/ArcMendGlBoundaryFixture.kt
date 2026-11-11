package com.wotagei.cam.camera

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * ArcMendPass 的独立 GLES/Diag 边界桩。
 *
 * 只把真实 ArcMendPass 与 Shaders 字节码放进隔离 ClassLoader；资源句柄、FBO 状态、
 * program 链接结果和删除账由本文件生成的最小 Java 桩记录，不执行真实 GLES 或像素。
 */
internal class ArcMendGlBoundaryFixture(classes: File, parent: ClassLoader) : Closeable {
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                SOURCES.keys.any { name == it || name.startsWith("$it\$") } -> findClass(name)
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

    private val gles = loader.loadClass("android.opengl.GLES20")
    private val passClass = loader.loadClass("com.wotagei.cam.camera.ArcMendPass")
    private val pass: Any = passClass.declaredConstructors.single { it.parameterCount == 0 }
        .apply { isAccessible = true }
        .newInstance()

    data class TargetResources(val textures: List<Int>, val framebuffers: List<Int>)

    data class LiveResources(
        val textures: List<Int>,
        val framebuffers: List<Int>,
        val shaders: List<Int>,
        val programs: List<Int>
    ) {
        val isEmpty: Boolean
            get() = textures.isEmpty() && framebuffers.isEmpty() &&
                shaders.isEmpty() && programs.isEmpty()
    }

    fun ensure(width: Int, height: Int): Boolean = call("ensure", width, height) as Boolean

    fun release() {
        call<Unit>("release")
    }

    fun failFramebufferAt(checkNumber: Int) {
        gl("probeFailFramebufferAt", checkNumber)
    }

    fun failProgramLinkAt(checkNumber: Int) {
        gl("probeFailProgramLinkAt", checkNumber)
    }

    val generatedTextures: List<Int>
        get() = ints("probeGeneratedTextures")

    val generatedFramebuffers: List<Int>
        get() = ints("probeGeneratedFramebuffers")

    val framebufferStatuses: List<Int>
        get() = ints("probeFramebufferStatuses")

    val live: LiveResources
        get() = LiveResources(
            ints("probeLiveTextures"),
            ints("probeLiveFramebuffers"),
            ints("probeLiveShaders"),
            ints("probeLivePrograms")
        )

    val targetResources: TargetResources
        get() = TargetResources(
            generatedTextures.takeLast(TARGET_COUNT),
            generatedFramebuffers.takeLast(TARGET_COUNT)
        )

    fun size(texture: Int): List<Int> = ints("probeTextureSize", texture)

    fun deleteCount(id: Int): Int = gl("probeDeleteCount", id) as Int

    private fun ints(name: String, vararg args: Any?): List<Int> =
        (gl(name, *args) as IntArray).toList()

    private fun <T> call(name: String, vararg args: Any?): T = unwrap {
        passClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }
            .invoke(pass, *args) as T
    }

    private fun gl(name: String, vararg args: Any?): Any? = unwrap {
        gles.methods.single { it.name == name && it.parameterCount == args.size }
            .invoke(null, *args)
    }

    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    override fun close() {
        loader.close()
    }

    companion object {
        private const val TARGET_COUNT = 5
        private const val COMPLETE = 0x8CD5

        private val REAL = setOf(
            "com.wotagei.cam.camera.ArcMendPass",
            "com.wotagei.cam.camera.Shaders"
        )

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val sources = SOURCES.map { (name, body) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    parentFile.mkdirs()
                    writeText(body.trimIndent())
                }
            }
            val compiler = Class.forName("javax.tools.ToolProvider")
                .getMethod("getSystemJavaCompiler")
                .invoke(null)
                ?: error("ArcMend 边界需要已有宿主 JDK；缺失编译器不是有效行为 RED")
            val output = ByteArrayOutputStream()
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) +
                sources.map { it.absolutePath }
            val exit = Class.forName("javax.tools.Tool")
                .getMethod(
                    "run",
                    InputStream::class.java,
                    OutputStream::class.java,
                    OutputStream::class.java,
                    Array<String>::class.java
                )
                .invoke(compiler, null, output, output, options.toTypedArray()) as Int
            check(exit == 0) {
                "ArcMend 边界编译失败不是有效行为 RED：\n${output.toString("UTF-8")}"
            }
            return classes
        }

        private val SOURCES = linkedMapOf(
            "android.opengl.GLES11Ext" to """
                package android.opengl;
                public final class GLES11Ext {
                    public static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
                }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public final class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public void w(String tag, String message) {}
                }
            """,
            "com.wotagei.cam.camera.GlRenderEngineKt" to """
                package com.wotagei.cam.camera;
                import java.nio.ByteBuffer;
                import java.nio.ByteOrder;
                import java.nio.FloatBuffer;
                public final class GlRenderEngineKt {
                    public static FloatBuffer nativeFloatBuffer(float[] values) {
                        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer();
                        result.put(values);
                        result.position(0);
                        return result;
                    }
                }
            """,
            "android.opengl.GLES20" to """
                package android.opengl;

                import java.nio.Buffer;
                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.LinkedHashMap;
                import java.util.LinkedHashSet;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                public final class GLES20 {
                    public static final int GL_TEXTURE_2D = 0x0DE1;
                    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
                    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
                    public static final int GL_TEXTURE_WRAP_S = 0x2802;
                    public static final int GL_TEXTURE_WRAP_T = 0x2803;
                    public static final int GL_LINEAR = 0x2601;
                    public static final int GL_CLAMP_TO_EDGE = 0x812F;
                    public static final int GL_RGBA = 0x1908;
                    public static final int GL_UNSIGNED_BYTE = 0x1401;
                    public static final int GL_FRAMEBUFFER = 0x8D40;
                    public static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
                    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
                    public static final int GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT = 0x8CD6;
                    public static final int GL_VERTEX_SHADER = 0x8B31;
                    public static final int GL_FRAGMENT_SHADER = 0x8B30;
                    public static final int GL_COMPILE_STATUS = 0x8B81;
                    public static final int GL_LINK_STATUS = 0x8B82;
                    public static final int GL_TRIANGLE_STRIP = 0x0005;
                    public static final int GL_FLOAT = 0x1406;
                    public static final int GL_TEXTURE0 = 0x84C0;
                    public static final int GL_NO_ERROR = 0;

                    private static final Set<Integer> liveTextures = new LinkedHashSet<>();
                    private static final Set<Integer> liveFramebuffers = new LinkedHashSet<>();
                    private static final Set<Integer> liveShaders = new LinkedHashSet<>();
                    private static final Set<Integer> livePrograms = new LinkedHashSet<>();
                    private static final List<Integer> generatedTextures = new ArrayList<>();
                    private static final List<Integer> generatedFramebuffers = new ArrayList<>();
                    private static final List<Integer> framebufferStatuses = new ArrayList<>();
                    private static final Map<Integer, Integer> deleteCounts = new HashMap<>();
                    private static final Map<Integer, int[]> textureSizes = new LinkedHashMap<>();
                    private static final Map<Integer, Integer> framebufferTextures = new HashMap<>();
                    private static int nextId = 100;
                    private static int boundTexture = 0;
                    private static int boundFramebuffer = 0;
                    private static int framebufferCheckCount = 0;
                    private static int framebufferFailureAt = 0;
                    private static int programLinkCheckCount = 0;
                    private static int programLinkFailureAt = 0;

                    private static int[] copy(List<Integer> values) {
                        int[] result = new int[values.size()];
                        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
                        return result;
                    }

                    private static int[] copy(Set<Integer> values) {
                        return copy(new ArrayList<>(values));
                    }

                    private static void deleted(int id) {
                        deleteCounts.put(id, deleteCounts.getOrDefault(id, 0) + 1);
                    }

                    public static void probeFailFramebufferAt(int checkNumber) {
                        framebufferFailureAt = checkNumber;
                        framebufferCheckCount = 0;
                        framebufferStatuses.clear();
                    }

                    public static void probeFailProgramLinkAt(int checkNumber) {
                        programLinkFailureAt = checkNumber;
                        programLinkCheckCount = 0;
                    }

                    public static int[] probeGeneratedTextures() { return copy(generatedTextures); }
                    public static int[] probeGeneratedFramebuffers() { return copy(generatedFramebuffers); }
                    public static int[] probeFramebufferStatuses() { return copy(framebufferStatuses); }
                    public static int[] probeLiveTextures() { return copy(liveTextures); }
                    public static int[] probeLiveFramebuffers() { return copy(liveFramebuffers); }
                    public static int[] probeLiveShaders() { return copy(liveShaders); }
                    public static int[] probeLivePrograms() { return copy(livePrograms); }
                    public static int probeDeleteCount(int id) {
                        return deleteCounts.getOrDefault(id, 0);
                    }
                    public static int[] probeTextureSize(int id) {
                        int[] size = textureSizes.get(id);
                        if (size == null) throw new AssertionError("没有该纹理的尺寸账：" + id);
                        return new int[] { size[0], size[1] };
                    }

                    public static void glGenTextures(int count, int[] ids, int offset) {
                        for (int i = 0; i < count; i++) {
                            int id = nextId++;
                            ids[offset + i] = id;
                            generatedTextures.add(id);
                            liveTextures.add(id);
                        }
                    }

                    public static void glBindTexture(int target, int id) {
                        if (target == GL_TEXTURE_2D) boundTexture = id;
                    }

                    public static void glTexParameteri(int target, int name, int value) {}

                    public static void glTexImage2D(
                        int target, int level, int internal, int width, int height, int border,
                        int format, int type, Buffer data
                    ) {
                        if (target != GL_TEXTURE_2D || boundTexture == 0) {
                            throw new AssertionError("非法纹理存储定义");
                        }
                        textureSizes.put(boundTexture, new int[] { width, height });
                    }

                    public static void glDeleteTextures(int count, int[] ids, int offset) {
                        for (int i = 0; i < count; i++) {
                            int id = ids[offset + i];
                            deleted(id);
                            liveTextures.remove(id);
                            textureSizes.remove(id);
                        }
                    }

                    public static void glGenFramebuffers(int count, int[] ids, int offset) {
                        for (int i = 0; i < count; i++) {
                            int id = nextId++;
                            ids[offset + i] = id;
                            generatedFramebuffers.add(id);
                            liveFramebuffers.add(id);
                        }
                    }

                    public static void glBindFramebuffer(int target, int id) {
                        if (target == GL_FRAMEBUFFER) boundFramebuffer = id;
                    }

                    public static void glFramebufferTexture2D(
                        int target, int attachment, int textureTarget, int texture, int level
                    ) {
                        framebufferTextures.put(boundFramebuffer, texture);
                    }

                    public static int glCheckFramebufferStatus(int target) {
                        framebufferCheckCount++;
                        int status;
                        if (framebufferCheckCount == framebufferFailureAt) {
                            status = GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
                        } else {
                            Integer texture = framebufferTextures.get(boundFramebuffer);
                            status = texture != null && liveTextures.contains(texture)
                                ? GL_FRAMEBUFFER_COMPLETE : GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
                        }
                        framebufferStatuses.add(status);
                        return status;
                    }

                    public static void glDeleteFramebuffers(int count, int[] ids, int offset) {
                        for (int i = 0; i < count; i++) {
                            int id = ids[offset + i];
                            deleted(id);
                            liveFramebuffers.remove(id);
                            framebufferTextures.remove(id);
                        }
                    }

                    public static int glCreateShader(int type) {
                        int id = nextId++;
                        liveShaders.add(id);
                        return id;
                    }

                    public static void glShaderSource(int shader, String source) {}
                    public static void glCompileShader(int shader) {}

                    public static void glGetShaderiv(int shader, int pname, int[] params, int offset) {
                        params[offset] = 1;
                    }

                    public static String glGetShaderInfoLog(int shader) { return ""; }

                    public static void glDeleteShader(int shader) {
                        deleted(shader);
                        liveShaders.remove(shader);
                    }

                    public static int glCreateProgram() {
                        int id = nextId++;
                        livePrograms.add(id);
                        return id;
                    }

                    public static void glAttachShader(int program, int shader) {}
                    public static void glLinkProgram(int program) {}

                    public static void glGetProgramiv(int program, int pname, int[] params, int offset) {
                        if (pname == GL_LINK_STATUS) {
                            programLinkCheckCount++;
                            params[offset] = programLinkCheckCount == programLinkFailureAt ? 0 : 1;
                        } else {
                            params[offset] = 1;
                        }
                    }

                    public static String glGetProgramInfoLog(int program) { return ""; }

                    public static void glDeleteProgram(int program) {
                        deleted(program);
                        livePrograms.remove(program);
                    }

                    public static int glGetAttribLocation(int program, String name) { return 1; }
                    public static int glGetUniformLocation(int program, String name) { return 1; }
                    public static void glUniform1i(int location, int value) {}
                    public static void glUniform1f(int location, float value) {}
                    public static void glUniformMatrix4fv(
                        int location, int count, boolean transpose, float[] value, int offset
                    ) {}
                    public static void glUseProgram(int program) {}
                    public static void glEnableVertexAttribArray(int index) {}
                    public static void glViewport(int x, int y, int width, int height) {}
                    public static void glVertexAttribPointer(
                        int index, int size, int type, boolean normalized, int stride, Buffer pointer
                    ) {}
                    public static void glActiveTexture(int texture) {}
                    public static void glDrawArrays(int mode, int first, int count) {}
                    public static int glGetError() { return GL_NO_ERROR; }
                }
            """
        )
    }
}
