package com.wotagei.cam.backup

import com.wotagei.cam.media.MediaTag
import com.wotagei.cam.media.MediaTagDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** 加载真实 Agent / 改名 / 启动字节码；仅替换 Android、Room 与 sidecar 边界，不复制恢复或导出编排。 */
internal class BackupPlatformFixture(classes: File, val root: File) : Closeable {
    private val real = setOf("com.wotagei.cam.backup.WotaBackupAgent", "com.wotagei.cam.media.MediaActions",
        "com.wotagei.cam.media.MediaOp", "com.wotagei.cam.media.VideoClip", "com.wotagei.cam.WotaApp")
    private val parent = javaClass.classLoader!!
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                real.any { name == it || name.startsWith("$it\$") } -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少实际生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                SOURCES.keys.any { name == it || name.startsWith("$it\$") } -> findClass(name)
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
    val rows = arrayListOf<MediaTag>()
    var pathMoves = 0
        private set
    val staged = File(root, "files/${PortableUserData.FILE_NAME}")
    val pending = File(root, "noBackup/${PortableUserData.FILE_NAME}")
    private val agent: Any
    private val context: Any

    init {
        requireNotNull(staged.parentFile).mkdirs()
        requireNotNull(pending.parentFile).mkdirs()
        script("root", root)
        val dao = Proxy.newProxyInstance(parent, arrayOf(MediaTagDao::class.java)) { _, method, args ->
            when (method.name) {
                "observeAll" -> flowOf(rows.toList())
                "insert" -> {
                    val row = args!![0] as MediaTag
                    rows.removeAll { it.mediaId == row.mediaId && it.tag == row.tag }
                    rows += row
                    Unit
                }
                "movePathAfterRename" -> {
                    pathMoves++
                    val id = args!![0] as Long
                    val oldPath = args[1] as String
                    val newPath = args[2] as String
                    // DAO 是平台边界；SQL 的同一条件另有实际源码接线断言，不能只认 mediaId。
                    rows.indices.forEach { i ->
                        val row = rows[i]
                        if (row.mediaId == id && row.path == oldPath) rows[i] = row.copy(path = newPath)
                    }
                    Unit
                }
                else -> error("未脚本化的 DAO 调用：${method.name}")
            }
        }
        type("com.wotagei.cam.media.WotaDb").getField("dao").set(null, dao)
        context = type("android.content.Context").getDeclaredConstructor().newInstance()
        agent = type("com.wotagei.cam.backup.WotaBackupAgent").getDeclaredConstructor().newInstance()
    }

    private fun type(name: String): Class<*> = loader.loadClass(name)
    fun script(name: String, value: Any?) = type("probe.BackupScript").getField(name).set(null, value)
    fun script(name: String): Any? = type("probe.BackupScript").getField(name).get(null)
    @Suppress("UNCHECKED_CAST")
    val memory: MutableMap<String, Any> get() = script("memory") as MutableMap<String, Any>
    @Suppress("UNCHECKED_CAST")
    val disk: MutableMap<String, Any> get() = script("disk") as MutableMap<String, Any>
    fun seedSettings(settings: Map<String, Any>) { memory.putAll(settings); disk.putAll(settings) }
    fun write(file: File, snapshot: PortableUserData.Snapshot) =
        file.outputStream().use { PortableUserData.write(snapshot, it) }
    fun read(file: File): PortableUserData.Snapshot = file.inputStream().use { PortableUserData.read(it) }
    private fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        target.javaClass.methods.single { it.name.substringBefore('$') == name && it.parameterCount == args.size }
            .invoke(target, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }
    private suspend fun suspending(target: Any, name: String, vararg args: Any?): Any? = suspendCoroutine { c ->
        try {
            val result = call(target, name, *args, c)
            if (result !== COROUTINE_SUSPENDED) c.resume(result)
        } catch (e: Throwable) { c.resumeWithException(e) }
    }
    fun restoreFinished() { call(agent, "onRestoreFinished") }
    fun startup() { call(type("com.wotagei.cam.WotaApp").getDeclaredConstructor().newInstance(), "onCreate") }
    fun backup(): PortableUserData.Snapshot {
        call(agent, "onFullBackup", type("android.app.backup.FullBackupDataOutput").getDeclaredConstructor().newInstance())
        return PortableUserData.read((script("published") as ByteArray).inputStream())
    }
    suspend fun restoreMedia(clips: List<Any> = emptyList()): List<*> {
        val cls = type("com.wotagei.cam.backup.WotaBackupAgent")
        return suspending(requireNotNull(cls.getField("Companion").get(null)), "restoreTags", context, clips) as List<*>
    }
    fun clip(path: String = OLD_PATH, name: String = "old.mp4", id: Long = 7L): Any {
        val uri = type("android.net.Uri").getConstructor(String::class.java).newInstance("content://media/external/video/media/$id")
        return type("com.wotagei.cam.media.VideoClip").declaredConstructors.single { it.parameterCount == 16 }
            .newInstance(id, uri, name, path, "Movies/", 0L, 123L, 1000L, 640, 480,
                "video/mp4", emptySet<String>(), false, null, null, 1)
    }
    suspend fun rename(clip: Any, name: String = "renamed"): Any? {
        val repo = type("com.wotagei.cam.media.MediaRepo").getDeclaredConstructor().newInstance()
        val actions = type("com.wotagei.cam.media.MediaActions").declaredConstructors.single { it.parameterCount == 3 }
            .apply { isAccessible = true }.newInstance(context, repo, CoroutineScope(Dispatchers.Unconfined))
        return suspending(actions, "rename", clip, name)
    }
    override fun close() = loader.close()

    companion object {
        const val OLD_PATH = "/storage/emulated/0/Movies/old.mp4"
        const val NEW_PATH = "/storage/emulated/0/Movies/renamed (1).mp4"

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val files = SOURCES.map { (name, text) ->
                File(root, name.replace('.', '/') + ".java").apply { requireNotNull(parentFile).mkdirs(); writeText(text.trimIndent()) }
            }
            // Android 单测编译类路径不含 javax.tools，按本仓既有 fixture 用反射访问运行测试的 JDK。
            val compiler = requireNotNull(Class.forName("javax.tools.ToolProvider")
                .getMethod("getSystemJavaCompiler").invoke(null)) { "测试需要现有 JDK" }
            val classpath = listOf(MediaTagDao::class.java, kotlin.Unit::class.java, CoroutineScope::class.java)
                .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).absolutePath }
                .distinct().joinToString(File.pathSeparator)
            val run = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
            val arguments = listOf("-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", classes.absolutePath) +
                files.map { it.absolutePath }
            assertEquals("平台桩必须编译成功，不能拿基础设施错误当 RED", 0,
                run.invoke(compiler, null, null, null, arguments.toTypedArray()))
            return classes
        }

        private val SOURCES = mapOf(
            "probe.BackupScript" to """
                package probe;
                public class BackupScript {
                    public static java.io.File root;
                    public static final java.util.Map<String,Object> memory=new java.util.LinkedHashMap<>(), disk=new java.util.LinkedHashMap<>();
                    public static boolean commitOk=true, denyUpdate=false, unreadableAfterRename=false;
                    public static int commits=0, pendingFailures=0, failedBytes=0, backupCalls=0, updateResult=1;
                    public static String failWritePath, failReadPath;
                    public static byte[] published;
                    public static String path="/storage/emulated/0/Movies/old.mp4", name="old.mp4";
                    public static final String actualName="renamed (1).mp4";
                }
            """,
            "android.content.SharedPreferences" to """
                package android.content;
                public interface SharedPreferences {
                    java.util.Map<String,?> getAll(); Editor edit();
                    interface Editor {
                        Editor putBoolean(String k,boolean v); Editor putInt(String k,int v); Editor putLong(String k,long v);
                        Editor putFloat(String k,float v); Editor putString(String k,String v); boolean commit();
                    }
                }
            """,
            "probe.Prefs" to """
                package probe;
                public class Prefs implements android.content.SharedPreferences {
                    public java.util.Map<String,?> getAll(){return new java.util.LinkedHashMap<>(BackupScript.memory);}
                    public Editor edit(){return new Edit();}
                    static class Edit implements Editor {
                        final java.util.Map<String,Object> changes=new java.util.LinkedHashMap<>();
                        public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
                        public Editor putInt(String k,int v){changes.put(k,v);return this;}
                        public Editor putLong(String k,long v){changes.put(k,v);return this;}
                        public Editor putFloat(String k,float v){changes.put(k,v);return this;}
                        public Editor putString(String k,String v){changes.put(k,v);return this;}
                        public boolean commit(){
                            BackupScript.commits++; BackupScript.memory.putAll(changes);
                            if(!BackupScript.commitOk)return false;
                            BackupScript.disk.clear();BackupScript.disk.putAll(BackupScript.memory);return true;
                        }
                    }
                }
            """,
            "android.content.Context" to """
                package android.content;
                public class Context {
                    public java.io.File getFilesDir(){return new java.io.File(probe.BackupScript.root,"files");}
                    public java.io.File getNoBackupFilesDir(){return new java.io.File(probe.BackupScript.root,"noBackup");}
                    public Context getApplicationContext(){return this;}
                    public SharedPreferences getSharedPreferences(String n,int m){return new probe.Prefs();}
                    public ContentResolver getContentResolver(){return new ContentResolver();}
                    public String getString(int id){return "test";}
                    public <T> T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
                }
            """,
            "android.app.Application" to """
                package android.app; public class Application extends android.content.Context { public void onCreate(){} }
            """,
            "android.app.NotificationChannel" to """
                package android.app; public class NotificationChannel {public NotificationChannel(String i,CharSequence n,int p){} public void setDescription(String d){} }
            """,
            "android.app.NotificationManager" to """
                package android.app; public class NotificationManager {public void createNotificationChannel(NotificationChannel c){} }
            """,
            "android.os.StrictMode" to """
                package android.os;
                public class StrictMode {
                    public static void setThreadPolicy(ThreadPolicy p){} public static void setVmPolicy(VmPolicy p){}
                    public static class ThreadPolicy {public static class Builder {public Builder detectAll(){return this;} public Builder penaltyLog(){return this;} public ThreadPolicy build(){return new ThreadPolicy();}}}
                    public static class VmPolicy {public static class Builder {public Builder detectAll(){return this;} public Builder penaltyLog(){return this;} public VmPolicy build(){return new VmPolicy();}}}
                }
            """,
            "android.os.ParcelFileDescriptor" to "package android.os; public class ParcelFileDescriptor {}",
            "android.app.backup.BackupDataInput" to "package android.app.backup; public class BackupDataInput {}",
            "android.app.backup.BackupDataOutput" to "package android.app.backup; public class BackupDataOutput {}",
            "android.app.backup.FullBackupDataOutput" to "package android.app.backup; public class FullBackupDataOutput {}",
            "android.app.backup.BackupAgent" to """
                package android.app.backup;
                public class BackupAgent extends android.content.Context {
                    public void onRestoreFinished(){}
                    public void onFullBackup(FullBackupDataOutput data) throws java.io.IOException {
                        probe.BackupScript.backupCalls++;
                        probe.BackupScript.published=java.nio.file.Files.readAllBytes(new java.io.File(getFilesDir(),"wota_user_backup.bin").toPath());
                    }
                }
            """,
            "android.util.AtomicFile" to """
                package android.util;
                public class AtomicFile {
                    private final java.io.File base, next, bak;
                    public AtomicFile(java.io.File f){base=f;next=new java.io.File(f+".new");bak=new java.io.File(f+".bak");}
                    public java.io.FileInputStream openRead() throws java.io.IOException {
                        if(base.getPath().equals(probe.BackupScript.failReadPath))throw new java.io.IOException("injected read failure");
                        if(bak.exists())java.nio.file.Files.move(bak.toPath(),base.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return new java.io.FileInputStream(base);
                    }
                    public java.io.FileOutputStream startWrite() throws java.io.IOException {
                        return new java.io.FileOutputStream(next){
                            private int count;
                            public void write(int b)throws java.io.IOException{check(1);super.write(b);}
                            public void write(byte[] b,int o,int n)throws java.io.IOException{check(n);super.write(b,o,n);}
                            private void check(int n)throws java.io.IOException{
                                if(base.getPath().equals(probe.BackupScript.failWritePath)&&count>=32){
                                    probe.BackupScript.failedBytes=count;throw new java.io.IOException("injected partial write");
                                }count+=n;
                            }
                        };
                    }
                    public void finishWrite(java.io.FileOutputStream out) throws java.io.IOException {
                        out.getFD().sync();out.close();java.nio.file.Files.move(next.toPath(),base.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);bak.delete();
                    }
                    public void failWrite(java.io.FileOutputStream out) throws java.io.IOException {
                        probe.BackupScript.pendingFailures++;out.close();next.delete();
                    }
                    public void delete(){base.delete();next.delete();bak.delete();}
                }
            """,
            "android.net.Uri" to """
                package android.net; public class Uri {private final String s;public Uri(String s){this.s=s;}public String toString(){return s;}}
            """,
            "android.content.ContentUris" to """
                package android.content;public class ContentUris {public static android.net.Uri withAppendedId(android.net.Uri u,long id){return new android.net.Uri(u+"/"+id);}}
            """,
            "android.content.ContentValues" to """
                package android.content;public class ContentValues extends java.util.HashMap<String,Object>{public void put(String k,String v){super.put(k,v);}public String getAsString(String k){return (String)get(k);}}
            """,
            "android.database.Cursor" to """
                package android.database;public interface Cursor extends java.io.Closeable {boolean moveToFirst();boolean isNull(int i);String getString(int i);long getLong(int i);void close();}
            """,
            "android.content.ContentResolver" to """
                package android.content;
                public class ContentResolver {
                    public int update(android.net.Uri u,ContentValues v,String where,String[] args){
                        if(probe.BackupScript.denyUpdate)throw new SecurityException("injected denial");
                        if(probe.BackupScript.updateResult>0){
                            probe.BackupScript.name=probe.BackupScript.actualName;
                            probe.BackupScript.path="/storage/emulated/0/Movies/"+probe.BackupScript.actualName;
                        }return probe.BackupScript.updateResult;
                    }
                    public android.database.Cursor query(android.net.Uri u,String[] projection,String w,String[] a,String sort){
                        return new android.database.Cursor(){
                            public boolean moveToFirst(){return true;}
                            public String getString(int i){switch(projection[i]){
                                case "_data":return probe.BackupScript.unreadableAfterRename&&probe.BackupScript.name.equals(probe.BackupScript.actualName)?null:probe.BackupScript.path;
                                case "_display_name":return probe.BackupScript.name;
                                case "relative_path":return "Movies/";
                                default:throw new AssertionError(projection[i]);
                            }}
                            public boolean isNull(int i){return getString(i)==null;}
                            public long getLong(int i){return projection[i].equals("_size")?123L:1000L;}
                            public void close(){}
                        };
                    }
                }
            """,
            "android.provider.MediaStore" to """
                package android.provider;public class MediaStore {public static class Video {public static class Media {public static final android.net.Uri EXTERNAL_CONTENT_URI=new android.net.Uri("content://media/external/video/media");}}}
            """,
            "androidx.room.RoomDatabase" to "package androidx.room;public class RoomDatabase {}",
            "androidx.room.RoomDatabaseKt" to """
                package androidx.room;public class RoomDatabaseKt {
                    public static <T> Object withTransaction(RoomDatabase db,kotlin.jvm.functions.Function1<? super kotlin.coroutines.Continuation<? super T>,? extends Object> block,kotlin.coroutines.Continuation<? super T> c){return block.invoke(c);}
                }
            """,
            "com.wotagei.cam.media.WotaDb" to """
                package com.wotagei.cam.media;public class WotaDb extends androidx.room.RoomDatabase {
                    public static MediaTagDao dao;public MediaTagDao tagDao(){return dao;}
                    public static final Companion Companion=new Companion();public static class Companion {public WotaDb get(android.content.Context c){return new WotaDb();}}
                }
            """,
            "com.wotagei.cam.media.MediaRepo" to """
                package com.wotagei.cam.media;public class MediaRepo {public WotaDb db(){return new WotaDb();}public void invalidate(){} }
            """,
            "com.wotagei.cam.record.ArcDropLog" to """
                package com.wotagei.cam.record;public class ArcDropLog {public static final Companion Companion=new Companion();public static class Companion {public boolean renameFor(String a,String b){return true;} }}
            """,
            "com.wotagei.cam.core.WotaStorage" to """
                package com.wotagei.cam.core;public class WotaStorage {public static final WotaStorage INSTANCE=new WotaStorage();public boolean hasAllFilesAccess(){return true;}public boolean supportsAllFilesAccess(){return true;}}
            """,
            "com.wotagei.cam.ui.WotaSettings" to """
                package com.wotagei.cam.ui;public class WotaSettings {public static final WotaSettings INSTANCE=new WotaSettings();public android.content.SharedPreferences of(android.content.Context c){return c.getSharedPreferences("wota_settings",0);}public boolean diagVerbose(android.content.SharedPreferences p){return false;}public boolean diagEnabled(android.content.SharedPreferences p){return false;}}
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;public class Diag {public static final Diag INSTANCE=new Diag();public void w(String t,String m){}public void w(String t,String m,Throwable e){}public void install(android.app.Application a,boolean v,boolean e){} }
            """
        )
    }
}
