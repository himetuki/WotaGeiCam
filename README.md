# WotaGeiCam

「打师相机」——把 Android 版与 HarmonyOS 版放在同一个仓库里维护（main 一棵树管两端）。

```
WotaGeiCam/
├── Android/
│   └── WotaGeiCamApp/      # 安卓 Gradle 工程（入库）
├── HarmonyOS/
│   └── WotaGeiCamOHOS/     # 鸿蒙 DevEco 工程（入库）
├── .github/workflows/
│   ├── build-apk.yml       # v* tag → android-*.apk 四件套
│   └── build-hap.yml       # v* tag → ohos-*.hap（工具链自取，无需本机 DevEco）
├── tools/  node_modules/  docs/  research/  deliverable/  agent-memory/
│   camera-knowledge/  .zcode/  .mimosa/
│                           # 本地资产（工具/依赖/打包/记忆类），gitignored 不入库
├── LICENSE                 # GPL-3.0
└── README.md  .gitignore  .gitattributes
```

## 构建

**Android**（需要 Android SDK / JDK 17；目录结构沿用上游，Gradle 根在 `Android/WotaGeiCamApp/`）：

```bash
cd Android/WotaGeiCamApp
./gradlew :app:assembleRelease
```

产物在 `Android/WotaGeiCamApp/app/build/outputs/apk/release/`。签名材料（`keystore.properties`、`*.jks`）
不进仓库，CI 从 Actions Secrets 还原。

**HarmonyOS**（需要 DevEco Studio 的 hvigor 与 HarmonyOS SDK）：

```bash
cd HarmonyOS/WotaGeiCamOHOS
export DEVECO_SDK_HOME="$DEVECO_HOME/sdk"   # Windows Git Bash 示例
./hvigorw --mode module -p product=default assembleHap
```

产物在 `HarmonyOS/WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-unsigned.hap`。
纯逻辑测试：`cd HarmonyOS/WotaGeiCamOHOS && node scripts/pure-test.mjs`（235 用例）。

## 发布

打一个 `v*` tag，两条流水线并行跑，资产进同一个 Release：

```bash
git tag v0.0.10
git push origin v0.0.10
```

- APK：`android-v0.0.10-release.apk`、`android-v0.0.10-universal-release.apk`、
  `android-v0.0.10-arm64-v8a-release.apk`、`android-v0.0.10-armeabi-v7a-release.apk`
- HAP：`ohos-v0.0.10-unsigned.hap`（配置签名 Secrets 后追加 `-signed.hap`）+ `SHA256SUMS`

tag 带检查点号（如 `v0.0.10-r05`）时：

- APK 的 versionName 带 `-r05`（Gradle `-Prbuild`），产物名也带；
- HAP 的平台只允许 `数字.数字.数字` 形式的 versionName，所以包内 `versionName=0.0.10`、
  r 号折算进 `versionCode`（公式 `1000000 + major*1e6 + minor*1e4 + patch*100 + r`），
  产物名仍带 `-r05`。

### Actions Secrets

| Secret | 用途 |
| --- | --- |
| `WOTA_KEYSTORE_BASE64` / `WOTA_KEYSTORE_PROPS` | Android 发布签名（p12/jks + properties） |
| `HAP_KEYSTORE_BASE64` / `HAP_APP_CERT_BASE64` / `HAP_PROFILE_BASE64` | HAP 签名三件套（DevEco 自动签名导出） |
| `HAP_KEY_ALIAS` / `HAP_KEY_PASSWORD` / `HAP_KEYSTORE_PASSWORD` | HAP 密钥别名与口令 |

HAP 的 `profile.p7b` 必须是由 AGC/DevEco 签发、包含设备 UDID 的 profile，否则 signed 包也不能装零售机。
未配置 HAP 签名 Secrets 时只产 unsigned 包。

## 说明

- Android 与 HarmonyOS 两个工程互不 import，共享的只有产品语义与发布流程；
  跨端对齐记录见 `HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md`。
- 上游同步：`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md`（只读对比上游 Android 代码后手工移植）。
- HAP CI 使用华为 DevEco Linux 命令行工具（内置 HarmonyOS 6.1.1 / API 24 SDK），
  版本与 SHA256 固定在 `build-hap.yml`；镜像可用仓库变量 `HARMONY_CLI_TOOLS_URL` /
  `HARMONY_CLI_TOOLS_SHA256` 覆盖。

## 许可

整项目以 GPL-3.0-only 发布（SPDX: `GPL-3.0-only`），全文见 [LICENSE](LICENSE)。
Android 子树沿用上游（github.com/himetuki/WotaGeiCam）的 GPL v3 许可；HarmonyOS 移植工程同许可证。
