package com.wotagei.cam.camera

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * U03 独立边界：只替换 GLES20 和日志，EchoChain、Shaders、尺寸计算均执行生产字节码。
 *
 * GL 桩只记录存储定义域、清理覆盖域、绑定和状态：null 分配不等于黑，draw 读取未定义的
 * 2D 存储直接报 AssertionError。draw 的输出只标为“不透明的已写数据”，不执行 Echo 公式、
 * 不解释 GLSL 算术、不提供 glReadPixels，故这些测试不是驱动或真实像素验证。
 * 与 Camera/Export 等共享 fixture 没有依赖，生成的 Java 仅由主审运行测试时编译到工作区。
 */
internal class EchoGlBoundaryFixture(classes: File, parent: ClassLoader) : Closeable {
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
    val chain: Any = loader.loadClass("com.wotagei.cam.camera.EchoChain").declaredConstructors
        .single { it.parameterCount == 3 }.apply { isAccessible = true }
        .newInstance(
            { vertex: String, fragment: String -> gl("probeLink", vertex, fragment) as Int },
            { _: String -> gl("glGetError") as Int },
            floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        )
    private val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    fun call(name: String, vararg args: Any?): Any? = unwrap {
        chain.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(chain, *args)
    }
    fun gl(name: String, vararg args: Any?): Any? = unwrap {
        gles.methods.single { it.name == name && it.parameterCount == args.size }.invoke(null, *args)
    }
    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    /** 公开 draw 是缺陷复现入口；没有在调用前偷偷给生产 RT 清零。 */
    fun draw(w: Int = 8, h: Int = 6, trail: Boolean = false, rotated: Boolean = false): Boolean {
        val matrix = if (rotated) {
            floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
        } else {
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        }
        return call("draw", 77, matrix, uv, w, h, 1, 0.8f, trail, 0.8f, 0, 1f, 1f, 1f, 0.1f) as Boolean
    }

    /** 单独执行真实分配，以便检查首个 draw 之前两张存储；不写 accTex/accFbo/accIdx。 */
    fun allocate(w: Int = 8, h: Int = 6): Boolean = call("ensureRenderTarget", w, h) as Boolean
    fun reset() { call("reset") }
    val resultTex: Int get() = call("getResultTex") as Int
    val textures: List<Int> get() = (gl("probeTextures") as IntArray).toList()
    val samples: List<Int> get() = (gl("probeSamples") as IntArray).toList()
    val writes: List<Int> get() = (gl("probeWrites") as IntArray).toList()
    fun defined(id: Int): Boolean = gl("probeDefined", id) as Boolean
    fun black(id: Int): Boolean = gl("probeBlack", id) as Boolean
    fun size(id: Int): List<Int> = (gl("probeSize", id) as IntArray).toList()

    /**
     * 隔离 reset 的前置条件：用非空 GL 上传种入“已定义但非黑”的历史。
     * 不改生产字段，不代表正常首帧流程；首帧和换尺寸用例一律不用此辅助。
     */
    fun uploadDefinedHistory() {
        val active = integer(0x84E0)
        val bound = integer(0x8069)
        for (id in textures) {
            val (w, h) = size(id)
            val data = ByteBuffer.allocateDirect(w * h * 4)
            while (data.hasRemaining()) data.put(0x55.toByte())
            data.flip()
            gl("glBindTexture", 0x0DE1, id)
            gl("glTexImage2D", 0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, data)
        }
        gl("glBindTexture", 0x0DE1, bound)
        gl("glActiveTexture", active)
    }

    data class State(
        val framebuffer: Int,
        val viewport: List<Int>,
        val scissorEnabled: Boolean,
        val scissorBox: List<Int>,
        val colorMask: List<Boolean>,
        val clearColor: List<Float>,
        val program: Int
    )

    /** 仅比较清理应保护的状态；不把链约定的“采样单元归零”误当作全状态恢复契约。 */
    fun state(): State {
        val mask = BooleanArray(4)
        val clear = FloatArray(4)
        gl("glGetBooleanv", 0x0C23, mask, 0)
        gl("glGetFloatv", 0x0C22, clear, 0)
        return State(integer(0x8CA6), integers(0x0BA2, 4), gl("glIsEnabled", 0x0C11) as Boolean,
            integers(0x0C10, 4), mask.toList(), clear.toList(), integer(0x8B8D))
    }

    /** 外部 FBO 为不属于本 fixture 纹理池的调用者目标；误清该目标会立即报错。 */
    fun callerState(scissor: Boolean = false, masked: Boolean = false) {
        gl("glBindFramebuffer", 0x8D40, 900_001)
        gl("glViewport", 3, 4, 17, 19)
        gl("glScissor", 1, 1, 2, 2)
        gl(if (scissor) "glEnable" else "glDisable", 0x0C11)
        gl("glColorMask", true, !masked, true, !masked)
        gl("glClearColor", 0.25f, 0.5f, 0.75f, 0.125f)
        gl("glUseProgram", 900_002)
    }

    private fun integer(key: Int): Int = integers(key, 1).single()
    private fun integers(key: Int, count: Int): List<Int> = IntArray(count).also {
        gl("glGetIntegerv", key, it, 0)
    }.toList()

    override fun close() {
        try { call("release") } finally { loader.close() }
    }

    companion object {
        private val REAL = setOf("com.wotagei.cam.camera.EchoChain", "com.wotagei.cam.camera.Shaders")
        private fun floats(vararg data: Float): FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val sources = SOURCES.map { (name, body) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    parentFile.mkdirs()
                    writeText(body.trimIndent())
                }
            }
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
                ?: error("U03 边界需要已有宿主 JDK；缺失编译器不是有效行为 RED")
            val output = ByteArrayOutputStream()
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) +
                sources.map { it.absolutePath }
            val exit = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
                .invoke(compiler, null, output, output, options.toTypedArray()) as Int
            check(exit == 0) { "U03 边界编译失败不是有效行为 RED：\n${output.toString("UTF-8")}" }
            return classes
        }

        private val SOURCES = mapOf(
            "android.opengl.GLES11Ext" to """
                package android.opengl;
                public class GLES11Ext { public static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65; }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public void i(String tag, String message) {}
                    public void w(String tag, String message) {}
                }
            """,
            "android.opengl.GLES20" to """
                package android.opengl;
                import java.nio.*;
                import java.util.*;
                import java.util.regex.*;

                public class GLES20 {
                    // 只维护 GL 可见状态与定义域，不计算片元颜色或残影递推。
                    static final int TEX_2D=0x0DE1, EXTERNAL=0x8D65, TEXTURE0=0x84C0;
                    static final class Storage {
                        final int w,h;
                        final boolean[] defined, black;
                        Storage(int w,int h) {
                            this.w=w; this.h=h;
                            defined=new boolean[w*h*4]; black=new boolean[w*h*4];
                        }
                    }
                    static final class Program {
                        final Map<String,Integer> locations=new LinkedHashMap<>();
                        final Map<Integer,Integer> samplers=new LinkedHashMap<>();
                    }
                    static final Map<Integer,Storage> textures=new LinkedHashMap<>();
                    static final Map<Integer,Integer> attachments=new HashMap<>();
                    static final Map<Integer,Map<Integer,Integer>> bindings=new HashMap<>();
                    static final Map<Integer,Program> programs=new HashMap<>();
                    static final Map<Integer,Integer> uniformInts=new HashMap<>();
                    static final List<Integer> samples=new ArrayList<>(), writes=new ArrayList<>();
                    static final Set<Integer> fbos=new HashSet<>();
                    static int nextId=100, nextLocation=1, framebuffer=0, program=0, active=TEXTURE0;
                    static int[] viewport={0,0,8,6}, scissorBox={0,0,8,6};
                    static boolean scissor=false;
                    static boolean[] colorMask={true,true,true,true};
                    static float[] clearColor={0,0,0,0};

                    static boolean all(boolean[] bits) {
                        if(bits.length==0) return false;
                        for(boolean bit:bits) if(!bit) return false;
                        return true;
                    }
                    static Storage storage(int id) {
                        Storage s=textures.get(id);
                        if(s==null) throw new AssertionError("GL 纹理没有存储："+id);
                        return s;
                    }
                    static int binding(int unit,int target) {
                        Map<Integer,Integer> byTarget=bindings.get(unit);
                        return byTarget==null ? 0 : byTarget.getOrDefault(target,0);
                    }
                    static int targetTexture() {
                        Integer id=attachments.get(framebuffer);
                        if(id==null) throw new AssertionError("误写调用者或未附着的 FBO："+framebuffer);
                        return id;
                    }
                    public static int[] probeTextures() {
                        return textures.keySet().stream().mapToInt(Integer::intValue).toArray();
                    }
                    public static int[] probeSamples() { return samples.stream().mapToInt(Integer::intValue).toArray(); }
                    public static int[] probeWrites() { return writes.stream().mapToInt(Integer::intValue).toArray(); }
                    public static boolean probeDefined(int id) { return all(storage(id).defined); }
                    public static boolean probeBlack(int id) {
                        Storage s=storage(id); return all(s.defined) && all(s.black);
                    }
                    public static int[] probeSize(int id) { Storage s=storage(id); return new int[]{s.w,s.h}; }
                    public static int probeFramebufferCount() { return fbos.size(); }

                    // 链接是边界桩，仅从真实传入源码登记 uniform 类型/位置；不模拟编译或执行 GLSL。
                    public static int probeLink(String vertex,String fragment) {
                        int id=nextId++;
                        Program p=new Program();
                        Matcher m=Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(vertex+"\n"+fragment);
                        while(m.find()) {
                            if(p.locations.containsKey(m.group(2))) continue;
                            int loc=nextLocation++;
                            p.locations.put(m.group(2),loc);
                            if(m.group(1).equals("sampler2D")) p.samplers.put(loc,TEX_2D);
                            if(m.group(1).equals("samplerExternalOES")) p.samplers.put(loc,EXTERNAL);
                        }
                        programs.put(id,p); return id;
                    }
                    public static int glGetUniformLocation(int id,String name) {
                        Program p=programs.get(id); return p==null ? -1 : p.locations.getOrDefault(name,-1);
                    }
                    public static int glGetAttribLocation(int id,String name) { return name.equals("aPosition") ? 0 : 1; }
                    public static void glUniform1i(int location,int value) { uniformInts.put(location,value); }
                    public static void glUniform1f(int location,float value) {}
                    public static void glUniform3f(int location,float r,float g,float b) {}
                    public static void glUniformMatrix4fv(int location,int count,boolean transpose,float[] value,int offset) {}
                    public static void glUseProgram(int id) { program=id; }
                    public static void glDeleteProgram(int id) { programs.remove(id); }
                    public static void glEnableVertexAttribArray(int index) {}
                    public static void glDisableVertexAttribArray(int index) {}
                    public static void glVertexAttribPointer(int index,int size,int type,boolean normalized,int stride,Buffer pointer) {}
                    public static void glActiveTexture(int unit) { active=unit; }
                    public static void glBindTexture(int target,int id) {
                        bindings.computeIfAbsent(active,k->new HashMap<>()).put(target,id);
                    }
                    public static void glTexParameteri(int target,int name,int value) {}
                    public static void glGenTextures(int count,int[] ids,int offset) {
                        for(int i=0;i<count;i++) { int id=nextId++; ids[offset+i]=id; textures.put(id,null); }
                    }
                    public static void glDeleteTextures(int count,int[] ids,int offset) {
                        for(int i=0;i<count;i++) {
                            int id=ids[offset+i]; textures.remove(id);
                            for(Map<Integer,Integer> b:bindings.values()) b.replaceAll((k,v)->v==id ? 0 : v);
                        }
                    }
                    public static void glTexImage2D(int target,int level,int internal,int w,int h,int border,int format,int type,Buffer data) {
                        int id=binding(active,target);
                        if(target!=TEX_2D || id==0 || w<=0 || h<=0) throw new AssertionError("非法 GL 存储分配");
                        Storage s=new Storage(w,h);
                        if(data!=null) {
                            if(!(data instanceof ByteBuffer) || data.remaining()<w*h*4) throw new AssertionError("上传数据不足");
                            ByteBuffer bytes=((ByteBuffer)data).duplicate();
                            for(int i=0;i<s.defined.length;i++) {
                                s.defined[i]=true;
                                s.black[i]=(bytes.get() & 255)==(i%4==3 ? 255 : 0);
                            }
                        }
                        textures.put(id,s);
                    }
                    public static void glGenFramebuffers(int count,int[] ids,int offset) {
                        for(int i=0;i<count;i++) { ids[offset+i]=nextId++; fbos.add(ids[offset+i]); }
                    }
                    public static void glDeleteFramebuffers(int count,int[] ids,int offset) {
                        for(int i=0;i<count;i++) {
                            int id=ids[offset+i]; attachments.remove(id); fbos.remove(id);
                            if(framebuffer==id) framebuffer=0;
                        }
                    }
                    public static void glBindFramebuffer(int target,int id) { framebuffer=id; }
                    public static void glFramebufferTexture2D(int target,int attachment,int textureTarget,int id,int level) {
                        attachments.put(framebuffer,id);
                    }
                    public static int glCheckFramebufferStatus(int target) {
                        Integer id=attachments.get(framebuffer);
                        return id!=null && textures.get(id)!=null ? 0x8CD5 : 0x8CD6;
                    }
                    public static void glViewport(int x,int y,int w,int h) { viewport=new int[]{x,y,w,h}; }
                    public static void glScissor(int x,int y,int w,int h) { scissorBox=new int[]{x,y,w,h}; }
                    public static void glEnable(int cap) { if(cap==0x0C11) scissor=true; }
                    public static void glDisable(int cap) { if(cap==0x0C11) scissor=false; }
                    public static boolean glIsEnabled(int cap) { return cap==0x0C11 && scissor; }
                    public static void glColorMask(boolean r,boolean g,boolean b,boolean a) { colorMask=new boolean[]{r,g,b,a}; }
                    public static void glClearColor(float r,float g,float b,float a) { clearColor=new float[]{r,g,b,a}; }
                    static boolean inside(int x,int y,int[] box) {
                        return x>=box[0] && y>=box[1] && x<box[0]+box[2] && y<box[1]+box[3];
                    }
                    public static void glClear(int mask) {
                        if((mask & 0x4000)==0) return;
                        Storage s=storage(targetTexture());
                        // glClear 忽略 viewport，但遵守 scissor 和 colorMask。
                        for(int y=0;y<s.h;y++) for(int x=0;x<s.w;x++) {
                            if(scissor && !inside(x,y,scissorBox)) continue;
                            for(int c=0;c<4;c++) if(colorMask[c]) {
                                int i=(y*s.w+x)*4+c;
                                s.defined[i]=true;
                                s.black[i]=clearColor[c]==(c==3 ? 1f : 0f);
                            }
                        }
                    }
                    public static void glDrawArrays(int mode,int first,int count) {
                        Program p=programs.get(program);
                        if(p==null || p.samplers.isEmpty()) throw new AssertionError("边界未登记真实 program 的采样器");
                        int target=targetTexture();
                        for(Map.Entry<Integer,Integer> sampler:p.samplers.entrySet()) {
                            int unit=TEXTURE0+uniformInts.getOrDefault(sampler.getKey(),0);
                            int id=binding(unit,sampler.getValue());
                            if(sampler.getValue()==EXTERNAL) {
                                if(id==0) throw new AssertionError("相机 OES 输入未绑定");
                                continue;
                            }
                            if(id==target) throw new AssertionError("GL feedback loop：采样与写入同一纹理 "+id);
                            samples.add(id);
                            if(!probeDefined(id)) throw new AssertionError("U03：真实 draw 首次采样未初始化的 2D 纹理 "+id);
                        }
                        Storage s=storage(target);
                        // 不推演 shader 输出；仅把实际可写范围标为已定义、非清黑来源。
                        for(int y=0;y<s.h;y++) for(int x=0;x<s.w;x++) {
                            if(!inside(x,y,viewport) || (scissor && !inside(x,y,scissorBox))) continue;
                            for(int c=0;c<4;c++) if(colorMask[c]) {
                                int i=(y*s.w+x)*4+c; s.defined[i]=true; s.black[i]=false;
                            }
                        }
                        writes.add(target);
                    }
                    public static void glGetIntegerv(int key,int[] out,int offset) {
                        int[] value;
                        switch(key) {
                            case 0x0D33: value=new int[]{4096}; break;
                            case 0x8CA6: value=new int[]{framebuffer}; break;
                            case 0x8B8D: value=new int[]{program}; break;
                            case 0x0BA2: value=viewport; break;
                            case 0x0C10: value=scissorBox; break;
                            case 0x0C11: value=new int[]{scissor ? 1 : 0}; break;
                            case 0x84E0: value=new int[]{active}; break;
                            case 0x8069: value=new int[]{binding(active,TEX_2D)}; break;
                            default: throw new AssertionError("边界未实现的整型 GL 查询："+key);
                        }
                        System.arraycopy(value,0,out,offset,value.length);
                    }
                    public static void glGetBooleanv(int key,boolean[] out,int offset) {
                        if(key!=0x0C23) throw new AssertionError("边界未实现的布尔 GL 查询："+key);
                        System.arraycopy(colorMask,0,out,offset,4);
                    }
                    public static void glGetFloatv(int key,float[] out,int offset) {
                        if(key!=0x0C22) throw new AssertionError("边界未实现的浮点 GL 查询："+key);
                        System.arraycopy(clearColor,0,out,offset,4);
                    }
                    public static int glGetError() { return 0; }
                }
            """
        )
    }
}
