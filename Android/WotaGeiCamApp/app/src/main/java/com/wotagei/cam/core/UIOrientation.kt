package com.wotagei.cam.core

import android.content.pm.ActivityInfo

/**
 * 设置页「默认方向」两档（13 号计划第 6 条，持久化键 `ui_orientation`，默认 [LANDSCAPE]）。
 *
 * 存在的理由：改之前录制页也设 `SCREEN_ORIENTATION_FULL_USER`，用户不打开系统自动旋转就永远只能竖着录，
 * 与「所有优化优先适配横屏」的总原则相反。这里把「用户选的档」与「系统给的方向常量」之间的映射抽成纯函数，
 * 于是两档各落到哪个常量、默认是哪一档、坏串回落哪儿，都能在 JVM 上直接断言，不必靠真机杀进程重进。
 *
 * 关于 core 层引 android 常量（Constants.kt 顶部写着「core 只用纯数据类」）：
 * `ActivityInfo.SCREEN_ORIENTATION_*` 是 `static final int` 编译期常量（API 9 就有，远低于 minSdk 29），
 * 不触碰任何运行时对象，产出只是个 Int，交给调用方写进 `Activity.requestedOrientation`。
 * 与 [AfMode.cam2Mode] 是同一类「API 契约常量」，不是机型数值 —— 机型能力（传感器方向、支持哪些朝向）
 * 仍由 CameraCharacteristics 与显示系统在那一侧决定，这里一个都不硬编码。
 */
enum class UIOrientation(val persistValue: String) {

    /** 横屏使用：锁在横屏，但 90↔270 两个横屏朝向仍随重力自动翻转 */
    LANDSCAPE("landscape"),

    /** 竖屏使用：锁在竖屏，同样保留 0↔180 两个竖屏朝向的自动翻转 */
    PORTRAIT("portrait");

    companion object {

        val ALL: List<UIOrientation> = values().toList()

        /** 出厂默认：横屏使用（用户 2026-09-28 定稿） */
        val DEFAULT: UIOrientation = LANDSCAPE

        /**
         * 存进去的串按 [persistValue] 取，缺失 / null / 坏串一律回落到 [DEFAULT]，不抛。
         * 大小写不敏感：手写进 prefs 的 `LANDSCAPE` 也该认，免得一次手改就把默认档打回未定义行为。
         */
        fun fromPersistValue(raw: String?): UIOrientation =
            ALL.firstOrNull { it.persistValue.equals(raw, ignoreCase = true) } ?: DEFAULT

        /**
         * 用户档位 → `Activity.requestedOrientation` 的映射（唯一真源）。**对所有页生效**
         * （用户 2026-10-02 指令：「界面方向设置应当对所有页生效」）：横屏档 ⇒ `SENSOR_LANDSCAPE`、
         * 竖屏档 ⇒ `SENSOR_PORTRAIT`，设置 / 媒体库 / 播放器 / 对比页不再例外。
         *
         * - **必须是 SENSOR_LANDSCAPE / SENSOR_PORTRAIT，不是 LANDSCAPE / PORTRAIT** —— 后者把朝向钉死在
         *   单一方向，前者允许同一轴向的两个朝向随重力翻转。「横竖都能录、且反向横屏也要能用」是既有需求
         *   （#21），并且正反向横屏切换时窗口尺寸不变、Compose 的 `onSizeChanged` 不会触发，
         *   `ui/CameraScreen.kt` 那边专门挂了 DisplayListener 才跟着转（该文件里 90↔270 的注释）；
         *   锁成单方向会把这条路堵死。
         * - 改掉的旧语义（2026-10-02 前）：仅录制页与「编辑控件」页按档位锁，其余页恒 `FULL_USER`
         *   「不越界锁它们」。按用户指令改为全页统一后，连带效果是 app 内任何页都不再随系统自动旋转
         *   （这只影响本 app 前台时的朝向，退出 app 后系统旋转行为不受影响）；「编辑控件」页与录制页
         *   同方向也就自动成立，无需再按页面区分。
         */
        fun screenOrientationOf(orientation: UIOrientation): Int =
            when (orientation) {
                LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
    }
}
