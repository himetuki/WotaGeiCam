import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wotagei.cam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wotagei.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.0.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("wota") {
            // keystore.properties 不入库；缺失时 release 回退 debug 签名（见 README 已知缺口）
            val ks = rootProject.file("keystore.properties")
            if (ks.exists()) {
                val p = Properties().apply { ks.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    // r13 用的那张证书（keystore.properties 在则用它，不在则退回 debug 签名，与 release 同一条判据）
    val wotaSign = if (rootProject.file("keystore.properties").exists()) {
        signingConfigs.getByName("wota")
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 钩子在 debug 变体也**不通**：这条判据故意不跟 isDebuggable 走，
            // 免得以后有人以为"debug 包能装"就等于"钩子能验"（本机装不了 debug 包，见 docs/plan/13 §14.3）
            buildConfigField("boolean", "MERGE_HOOK", "false")
        }
        /**
         * #73 的取证变体：**release 同签名 + debuggable=true**。
         *
         * 为什么要这么拧：这台华为测试机上没有 `screenrecord`（实测 `inaccessible or not found`），
         * `screencap` 单张 464–541ms，而 FLUENT 档 `WotaMotion.COMMIT_MS = 750ms` ⇒ 整个动画窗口只够拍 1 帧，
         * 中间态只能靠**应用内把进度钉死**来取证。而钉进度的入口不能留给用户误触，
         * 又不能走 `BuildConfig.DEBUG`：换装 debug 包签名不同 ⇒ 必须 `adb uninstall` ⇒ 清空 `wota_settings`
         * 与 `wota_media.db`（用户设置与收藏，本项目的硬红线）。
         * 所以另开一枚与 r13 **同签名**的变体：`adb install -r` 覆盖即装即换、不动数据，
         * 钩子的开关由 [MERGE_HOOK] 这条 buildConfigField 挡（只在 debugHook 为 true，release 恒 false）。
         *
         * `matchingFallbacks` 是给"依赖只带 debug/release 两种构建类型属性"的 AAR 一个退路，
         * 没有它 AGP 在解析 androidx/material3 那些 aar 时会报"找不到 debugHook 的匹配"。
         */
        create("debugHook") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = wotaSign
            matchingFallbacks += listOf("release", "debug")
            buildConfigField("boolean", "MERGE_HOOK", "true")
        }
        /**
         * #74 加的**取证构建**：release 同签名 + debuggable + **不混淆**，并且是 `testBuildType`。
         *
         * 为什么要单独一枚，而不是复用 `debugHook` 或直接跑默认的 debug：
         * AGP 的仪器测试只跟 `testBuildType` 那一条变体走（默认 `debug`）。默认值在这台机上是**危险**的——
         * debug 包与 release 两张证书，装上就必须先 `adb uninstall`，而那会清空 `wota_settings` 与
         * `wota_media.db`（用户设置、收藏、tag）。把 `testBuildType` 钉到一枚与 release 同签名的变体上，
         * "跑仪器测试"与"不许清用户数据"就同时成立，不用每次靠人记住别用错命令。
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
            signingConfig = wotaSign
            matchingFallbacks += listOf("release", "debug")
            buildConfigField("boolean", "MERGE_HOOK", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = wotaSign
            buildConfigField("boolean", "MERGE_HOOK", "false")
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.navigation:navigation-compose:2.7.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    val media3 = "1.1.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
