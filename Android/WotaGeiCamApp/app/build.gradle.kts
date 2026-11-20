import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

// 版本单一来源：defaultConfig 与产物命名共用。
// r 号 = 本地检查点/CI tag（v0.0.7-r08）的 r 段：命令行/CI 传 -Prbuild=08（也接受 r08），
// 归一成 "-r08" 后缀拼进 appVersionName → versionName / BuildConfig.VERSION_NAME（设置页显示）
// / archivesName（产物名 WotaGeiCam-v0.0.7-r08-release.apk）全部连锁携带；
// versionCode 不含 r，r 变体之间覆盖安装不受影响。属性空白/缺失 → 无后缀，回退纯 appVersionBase。
val appVersionCode = 12
val appVersionBase = "0.0.12"
val rbuildProp = (project.findProperty("rbuild") as? String)?.trim().orEmpty().removePrefix("r")
val rSuffix = if (rbuildProp.isEmpty()) "" else "-r$rbuildProp"
val appVersionName = appVersionBase + rSuffix

// 产物命名：CI 传 -PapkBaseName=WotaGeiCam 时产物名带版本号（用户 10-03 指令），
// 如 WotaGeiCam-v0.0.4-release.apk；本地不传该属性保持 app-*.apk 旧名，
// 不动既有脚本（scripts/verify-on-device.sh 引用 app-debugHook.apk 这类路径）。
val apkBaseNameProp = project.findProperty("apkBaseName") as? String
base {
    archivesName.set(apkBaseNameProp?.let { "$it-v$appVersionName" } ?: "app")
}

// 双 flavor（docs/plan/33）：full/lite 产物分目录输出（outputs/apk/<flavor>/<buildType>/），
// flavor 段由打包脚本/CI 在拷贝时按目录补进文件名（AGP Kotlin DSL 的 per-variant
// outputFileName 重命名与 DomainObjectCollection.all 重载歧义冲突，不在 Gradle 侧做）。
// versionName/versionCode 不动（同版本互相覆盖安装、数据保留，用户裁决）。

// Y63：正式签名配置必须**完整**才算就绪——storeFile/storePassword/keyAlias/keyPassword
// 四项齐全，且 storeFile 指向的 keystore 真存在。缺一项就视为"没有正式签名"，
// 绝不再回退 debug 签名去产出"看起来像 release"的包。
// 注意：这里只在文件存在时读取本机私密配置，不打印任何属性值。
val ksFile = rootProject.file("keystore.properties")
val ksProps = Properties()
val ksLoaded = ksFile.isFile && runCatching {
    ksFile.inputStream().use { ksProps.load(it) }
}.isSuccess
val ksStoreFile = ksProps.getProperty("storeFile")?.trim().orEmpty()
val ksReady = ksLoaded && ksStoreFile.isNotEmpty() &&
    listOf("storePassword", "keyAlias", "keyPassword").all {
        !ksProps.getProperty(it).isNullOrBlank()
    } &&
    rootProject.file(ksStoreFile).isFile

android {
    namespace = "com.wotagei.cam"
    compileSdk = 34

    // CI 四件套开关（.github/workflows/build-apk.yml）：传 -PabiSplits=true 时 ABI 交给下方
    // splits.include 决定（plain/universal/arm64-v8a/armeabi-v7a 各一枚）；
    // 平时不传该属性，保持"仅 arm64-v8a"的本地默认行为不变。
    val ciAbiSplits = (project.findProperty("abiSplits") as? String)?.toBoolean() ?: false

    defaultConfig {
        applicationId = "com.wotagei.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        if (!ciAbiSplits) {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // 双 flavor（docs/plan/33，2026-11-07 用户口令）：full=有 AI 站位（模型组+推理+控件），
    // lite=无 AI 站位（仅保留站位圆钮导流：点击弹宣传浮层）。同包名同 versionCode——
    // 两包可互相覆盖安装（数据保留），应用内更新按 flavor 各推各包（更新互斥在更新器层）。
    // lite 的 versionName 带 "-lite" 后缀便于辨识；versionCode 不分。
    flavorDimensions += "ai"
    productFlavors {
        create("full") {
            dimension = "ai"
        }
        create("lite") {
            dimension = "ai"
            versionNameSuffix = "-lite"
        }
    }

    if (ciAbiSplits) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = true
            }
        }
    }

    // Y63：正式签名配置必须**完整**才算就绪（判据在文件顶部 ksReady）——
    // 缺一项就视为"没有正式签名"，绝不再回退 debug 签名去产出"看起来像 release"的包。
    signingConfigs {
        create("wota") {
            // keystore.properties 不入库；只有四项齐全时才真正装上签名材料。
            // 缺配置的后果由文件末尾的 release 任务闸门处理（fail fast），不是回退 debug。
            if (ksReady) {
                storeFile = rootProject.file(ksStoreFile)
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    // release 只有在正式签名就绪时才绑定签名；否则保持 unsigned，并由任务闸门拒绝打包。
    val wotaSign = if (ksReady) signingConfigs.getByName("wota") else null

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 钩子在 debug 变体也**不通**：这条判据故意不跟 isDebuggable 走，
            // 免得以后有人以为"debug 包能装"就等于"钩子能验"（本机装不了 debug 包，见 docs/plan/13 §14.3）
            buildConfigField("boolean", "MERGE_HOOK", "false")
            buildConfigField("boolean", "FROST_PROBE", "false")
            buildConfigField("boolean", "COMPARE_PROBE", "false")
            // B12 重组测量入口：与上面三枚同一条纪律（仅 debugHook/probe 为 true，见 ui/HudRecompProbe.kt）
            buildConfigField("boolean", "HUD_RECOMP_PROBE", "false")
        }
        /**
         * #73 的取证变体：**可调试 + R8 + 独立包名 + debug 签名**（Y64）。
         *
         * 为什么要这么拧：这台华为测试机上没有 `screenrecord`（实测 `inaccessible or not found`），
         * `screencap` 单张 464–541ms，而 FLUENT 档 `WotaMotion.COMMIT_MS = 750ms` ⇒ 整个动画窗口只够拍 1 帧，
         * 中间态只能靠**应用内把进度钉死**来取证。而钉进度的入口不能留给用户误触，
         * 又不能走 `BuildConfig.DEBUG`。
         *
         * Y64：早先这枚变体与 release **同包名、同正式证书**，于是它会成为正式包的覆盖安装候选，
         * 且承接设备上的用户数据、又开着调试与 Intent 探针——取证产物一旦脱离受控设备就很危险。
         * 现在改为 `applicationIdSuffix=".debughook"` + debug 签名：
         * - 与正式包**可共存**，各自独立的数据目录，取证包只碰自己的数据；
         * - 不再需要为了保留数据而覆盖正式包，`adb uninstall` 正式包也永远不会因它发生；
         * - 正式制品里不可能出现这枚包（CI 只从 release 目录取产物，并另有人证守卫）。
         *
         * 钩子的开关由 [MERGE_HOOK] 这条 buildConfigField 挡（只在 debugHook 为 true，release 恒 false）。
         *
         * `matchingFallbacks` 是给"依赖只带 debug/release 两种构建类型属性"的 AAR 一个退路，
         * 没有它 AGP 在解析 androidx/material3 那些 aar 时会报"找不到 debugHook 的匹配"。
         */
        create("debugHook") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = true
            applicationIdSuffix = ".debughook"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Y64：取证包用本机 debug 证书，与正式包彻底分离（不再共用 wotaSign）
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
            buildConfigField("boolean", "MERGE_HOOK", "true")
            // #84 霜探针：与 MERGE_HOOK 同一条纪律（debugHook 出证据，release/debug 恒关，见 ui/FrostProbe.kt）
            buildConfigField("boolean", "FROST_PROBE", "true")
            // 对比播放黑屏探针：同一纪律（debugHook 出证据，release/debug 恒关，见 ui/CompareProbe.kt）
            buildConfigField("boolean", "COMPARE_PROBE", "true")
            // B12 重组测量入口：同一纪律（debugHook 出证据，见 ui/HudRecompProbe.kt）
            buildConfigField("boolean", "HUD_RECOMP_PROBE", "true")
        }
        /**
         * #74 加的**取证构建**：可调试 + **不混淆** + 独立包名 + debug 签名（Y64），并且是 `testBuildType`。
         *
         * 为什么要单独一枚，而不是复用 `debugHook` 或直接跑默认的 debug：
         * AGP 的仪器测试只跟 `testBuildType` 那一条变体走（默认 `debug`）。默认 debug 与正式包
         * **包名相同、证书不同**，装上就必须先 `adb uninstall`，而那会清空 `wota_settings` 与
         * `wota_media.db`（用户设置、收藏、tag）。`probe` 用独立包名 + debug 证书：
         * 仪器测试包与正式包各自独立，既不碰正式包的数据，也不需要卸载正式包。
         *
         * 与 `debugHook` 的差别只有一处：**这里不混淆**。R8 会把测试 APK 里的 `@Test` 类名改掉，
         * `am instrument -e class com.wotagei.cam.XxxTest#method` 就点不到方法了；
         * 而 `debugHook` 那枚要保持与真实 release 尽量一致（带 R8），所以两枚各司其职：
         * `debugHook` 出截图与钩子态，`probe` 跑仪器测试。
         */
        create("probe") {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".probe"
            // Y64：仪器测试包同样与正式包分离，用本机 debug 证书
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
            buildConfigField("boolean", "MERGE_HOOK", "true")
            buildConfigField("boolean", "FROST_PROBE", "true")
            buildConfigField("boolean", "COMPARE_PROBE", "true")
            // B12 重组测量入口：probe 变体同样带（不混淆，仪器/JVM 取证都走它）
            buildConfigField("boolean", "HUD_RECOMP_PROBE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = wotaSign
            buildConfigField("boolean", "MERGE_HOOK", "false")
            buildConfigField("boolean", "FROST_PROBE", "false")
            buildConfigField("boolean", "COMPARE_PROBE", "false")
            // B12 重组测量入口：release 里连字段都不该点亮（见 ui/HudRecompProbe.kt 闸门）
            buildConfigField("boolean", "HUD_RECOMP_PROBE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 仪器测试只准跑在 `probe` 上：默认值 debug 与 release 不同签名，装上必须先 uninstall ⇒ 清空用户存档。
    testBuildType = "probe"

    buildFeatures {
        compose = true
        // #73：钩子的开关走变体专属字段 BuildConfig.MERGE_HOOK（AGP 8 默认关，不开就没有这个类）
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.2" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // AGP 8.1 不保证未压缩 .so 的 16KiB ZIP 对齐；压缩后由安装器解包加载。
        // ELF 自身的 PT_LOAD 仍须 >=16KiB，不能靠此项掩盖旧 native 库。
        jniLibs.useLegacyPackaging = true
    }

    // 站位模型组（方案 32 M2）：tflite 不压缩——Interpreter mmap 加载要求未压缩 assets
    androidResources {
        noCompress += "tflite"
    }
}

/**
 * Y63 的 fail-fast 闸门：没有完整正式签名时，**任何真正要产出/安装 release 包的任务**都直接失败。
 *
 * 为什么不用"配置期看到 keystore 缺失就整体失败"：本机日常要跑 assembleFullProbe / debug，
 * 它们不需要正式签名。闸门挂在 task graph 上，只有任务图里真的出现
 * `assemble*Release` / `bundle*Release` / `package*Release` / `install*Release` 时才拦——
 * 包括 `./gradlew build` / `assemble` 这种"间接带上 release"的入口（图里会出现具体变体任务）。
 *
 * 为什么不做"unsigned release"：本仓库的发布链路（build-apk.yml）一定会注入 keystore，
 * 本地缺配置只可能是漏配/换机；此时产出 unsigned 包同样会混进交付物，反而不如直接失败明确。
 */
val releasePackagingTask = Regex("(?i)^(assemble|bundle|package|install).*release$")
gradle.taskGraph.whenReady(object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
    override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
        if (!ksReady) {
            val offending = graph.allTasks.firstOrNull { releasePackagingTask.matches(it.name) }
            if (offending != null) {
                throw GradleException(
                    "缺少完整的正式签名配置，已拒绝产出 release 包（任务：${offending.path}）。\n" +
                        "要求：$ksFile 存在，且 storeFile/storePassword/keyAlias/keyPassword 四项齐全、" +
                        "storeFile 指向的 keystore 文件存在。\n" +
                        "本机只验证功能请用 :app:assembleFullProbe 或 :app:assembleFullDebug；\n" +
                        "需要正式包时把 keystore.properties 与 keystore 放回 Android/WotaGeiCamApp/（勿入库），" +
                        "CI 由 WOTA_KEYSTORE_BASE64 / WOTA_KEYSTORE_PROPS 注入。绝不回退 debug 签名。"
                )
            }
        }
    }
})

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // 保持 compileSdk 34：1.5.x 起库自身使用 SDK 35，不在安全维护轮扩工具链。
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // LiteRT 1.x 保留 org.tensorflow.lite Interpreter/GPU API，只进 full；不与旧 TF AAR 混装。
    // 1.4.2 的 CPU/GPU AAR 均含 arm64/v7a，ELF PT_LOAD 为 16KiB；设备推理另行验证。
    val litert = "1.4.2"
    "fullImplementation"("com.google.ai.edge.litert:litert:$litert")
    "fullImplementation"("com.google.ai.edge.litert:litert-gpu:$litert")
    "fullImplementation"("com.google.ai.edge.litert:litert-gpu-api:$litert")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    // Baseline Profile 生成源（coldstart-lever.md 路径 A）：四热路径采集模块
    baselineProfile(project(":baselineprofile"))
}
