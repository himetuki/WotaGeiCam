package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import java.io.Closeable

/**
 * 本轮专用调度边界：真实 withContext 将 IO 与返回 Main 的 continuation 分别入队。
 * 不替换 Controller/Engine/枚举/参数算法；原 recovery 和 N12 默认调度保持不变。
 */
internal class CameraStartupIntentFixture(parent: ClassLoader) : Closeable {
    val camera = CameraRecoveryStartFixture(parent, mapOf("kotlinx.coroutines.Dispatchers" to DISPATCHERS))
    private val dispatchers = camera.platform.type("kotlinx.coroutines.Dispatchers")

    fun ioPending(): Int = dispatchers.getMethod("getIoPending").invoke(null) as Int
    fun mainPending(): Int = dispatchers.getMethod("getMainPending").invoke(null) as Int
    fun runIo() {
        // Flow 的 combine/merge 会 yield 到 Main；先排空它们，只留下被测 IO 返回边界。
        runMain()
        dispatchers.getMethod("runNextIo").invoke(null)
    }
    fun runMain() { dispatchers.getMethod("drainMain").invoke(null) }

    fun releaseEnumeration() {
        assertEquals("必须确有挂起的生产 IO 工作，不能退化成 inline 自证", 1, ioPending())
        runIo()
        assertEquals("枚举已完成但生产 continuation 必须仍等待 Main", 1, mainPending())
        runMain()
    }

    fun finishEnumerations() {
        var remaining = 10
        while (ioPending() != 0) {
            check(remaining-- > 0) { "startup handoff 未收敛" }
            releaseEnumeration()
        }
    }

    fun startLive() {
        camera.bind()
        camera.start()
        releaseEnumeration()
        camera.drain()
        assertEquals(1, camera.opened())
        camera.completeOpen(0)
        camera.completeSession(0)
        camera.assertLive()
    }

    fun param(name: String): Any? {
        val params = camera.platform.call(camera.controller, "getParams")!!
        return camera.platform.call(camera.platform.call(params, "get$name")!!, "getValue")
    }

    override fun close() {
        // 先终止真实 Controller，使测试结束时未放行的 IO 不再拥有启动权，再清空平台队列。
        val current = camera.platform.field(camera.controller, "engine")
        camera.platform.call(camera.controller, "stop")
        if (current != null) camera.drain(current)
        runMain()
        camera.close()
        dispatchers.getMethod("clear").invoke(null)
    }

    companion object {
        private val DISPATCHERS = """
            package kotlinx.coroutines;
            public final class Dispatchers {
                public static final Dispatchers INSTANCE=new Dispatchers();
                private static boolean onMain=true;
                private static final java.util.ArrayDeque<Runnable> io=new java.util.ArrayDeque<>();
                private static final java.util.ArrayDeque<Runnable> main=new java.util.ArrayDeque<>();
                private static final MainCoroutineDispatcher MAIN=new MainCoroutineDispatcher(){
                    public MainCoroutineDispatcher getImmediate(){return this;}
                    public boolean isDispatchNeeded(kotlin.coroutines.CoroutineContext context){return !onMain;}
                    public void dispatch(kotlin.coroutines.CoroutineContext context,Runnable block){main.addLast(block);}
                };
                private static final CoroutineDispatcher IO=new CoroutineDispatcher(){
                    public void dispatch(kotlin.coroutines.CoroutineContext context,Runnable block){io.addLast(block);}
                };
                public static MainCoroutineDispatcher getMain(){return MAIN;}
                public static CoroutineDispatcher getIO(){return IO;}
                public static CoroutineDispatcher getDefault(){return MAIN;}
                public static CoroutineDispatcher getUnconfined(){return MAIN;}
                public static int getIoPending(){return io.size();}
                public static int getMainPending(){return main.size();}
                public static void runNextIo(){
                    if(io.isEmpty())throw new AssertionError("no suspended IO");
                    onMain=false;
                    try{io.removeFirst().run();}finally{onMain=true;}
                }
                public static void drainMain(){
                    if(!onMain)throw new AssertionError("not Main");
                    int remaining=1000;
                    while(!main.isEmpty()){
                        if(--remaining==0)throw new AssertionError("Main did not converge");
                        main.removeFirst().run();
                    }
                }
                public static void clear(){io.clear();main.clear();}
            }
        """
    }
}
