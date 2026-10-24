plugins {
    id("com.android.test")
    // 缺它则 .kt 源集静默不编译：APK 零测试类、collect 报 "no tests to run"（2026-10-24 实证）
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.wotagei.cam.bp"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        minSdk = 29
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // instrumentation 的 targetPackage 在 src/main/AndroidManifest.xml 显式声明：
        // AGP 8.1 的 Test DSL 无 targetPackage 属性，缺省落到测试模块自身
        // （com.wotagei.cam.bp）——零测试、"no test results" 的根因（2026-10-24 实证：
        // pm list instrumentation 显示 target=com.wotagei.cam.bp）
    }

    // 被测目标：:app 的 release（BGP 生成期自动建 nonMinifiedRelease 变体跑采集）
    targetProjectPath = ":app"
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
