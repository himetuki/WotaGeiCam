# F1 设置 / 备份 / 更新数据层（Android `ui`/`backup`/`update` → iOS `Settings` + `Update` + `WotaCore`）

只读基线：`Android/`，提交 `7394187`。本文件记录 **F1 数据层**的逐项映射、接口契约、
平台差异、切割取舍与人工验证清单。

- **源码状态**：源码已落，**未经 Apple 工具链编译**（本机 Windows 无 `xcodebuild`/`swift`，见文末）。
- **范围**：仅 `iOS/WotaGeiCam/Settings/`、`iOS/WotaGeiCam/Update/`、新增
  `iOS/Packages/WotaCore/Sources/WotaCore/Settings*.swift` / `Update*.swift` / `UserBackup*.swift`、本文件。
  未改动 `App/`、`Camera/`、`Library/`、`UI/`、`Config/`、工程、脚本、共享映射，也未改动既有核心文件。
- **平台口径**：iOS 10 基线（iPhone 5 armv7 / iPhone 6 arm64），UIKit；不用 SwiftUI / Combine /
  async-await / `@MainActor` / `Codable` / CryptoKit / `UniformTypeIdentifiers` / PHPicker。
- **AI 红线**：AI 站位（`stance_*` 键与 `stance/*`）**不实现、不移植、不保留占位**；
  备份白名单也剔除这 6 个键。
- **无自动联网**：更新探测**只由用户点击触发**，无定时器、无进入页面自动请求、无后台唤醒；
  所有 `URLSession` 代码只在 `iOS/WotaGeiCam/Update/`。

> 状态词：`源码已落（未编译）` ｜ `保留（平台差异）` ｜ `排除（AI，不移植）`。本文件不使用「完成/通过」等字样。

## 1. 键清单实测（重要发现：34 ≠ 注释里的 32）

- 入档白名单真源 = `Android/.../ui/ParamsPersist.kt` 的 `KEYS` 列表，**源实测 34 项**。
- `ParamsPersist.kt` 的 KDoc 注释写的是 **32 项**（历史遗留，与代码不符）。
- `iOS/docs/mappings/settings.md` 已写 **34 项**（与代码一致）；但
  `iOS/docs/mappings/features.md` 的 F-011 行仍写「32 键白名单」——**该处为陈旧的 32**。
- iOS 侧以**代码为准 = 34**：`WotaSettingsKeys.paramsKeys`（有序 34）与
  `WotaPersistedParams.fieldCount = 34` 为守卫值。
- 供主代理修映射：请把 `features.md` F-011 的「32 键」更正为「34 键」。
  （本任务只读共享映射，不自行改写。）

另一个映射口径偏差（同样交主代理）：`mappings/settings.md` S-033 `display_scale` 默认值写的是
「FULL」，但 Android 真源 `core/WotaParams.kt:28` 的 `DisplayScale` 只有
`ORIG/HALF/QUARTER/EIGHTH/SIXTEENTH`（无 `FULL`），**缺键默认 = `ORIG`**。iOS 侧按 `ORIG` 实现。

## 2. 两类持久化（iOS 逐类对齐，未合并）

承载：`UserDefaults(suiteName: "wota_settings")`（对齐 Android `SharedPreferences("wota_settings")`）。
取不到套件时退回 `.standard`（`WotaSettingsStore.standard()`）。

| 类别 | Android 真源 | iOS 落点 | 纪律 |
|---|---|---|---|
| 入档白名单（运行参数，34 键） | `ui/ParamsPersist.kt` `KEYS` / `restore` / `ParamsPersistSession.flush` | `WotaSettingsKeys.paramsKeys`、`WotaSettingsStore`、`WotaParamsRestore`、`WotaParamsPersistSession` | 冷启 `restore` 读键（坏值回默认/跳过）；用户动作完成点 `flush` 只写**与本次会话影子不同**的字段 |
| 直写 UI 设置 | `ui/SettingsScreen.kt` `object WotaSettings` 的 getter/`prefs.edit()` | `WotaSettingsStore` 直写 getter/setter | 低频直写；关键项（方向）写完 `flush()`（对齐 Android 对 `ui_orientation` 用 `commit()`） |

## 3. 入档白名单逐键（34 项）

`类型` 列：存储类型（`Int`/`Int64`/`Float`/`Bool`/`String`）。`读取钳制` 为 `WotaSettingsStore` getter 的口径
（对齐 Android `restore` 的「坏值回默认/跳过 + 产品域钳制」）。**写入只经 `WotaParamsPersistSession.flush`**。

| # | 键 | 类型 | 默认 | 读取钳制 | Android 源 |
|---|---|---|---|---|---|
| 1 | default_fps | Int | 25 | 钳到档位表首末（`WotaTiers.fps`） | `SettingsScreen.kt:132,374` |
| 2 | default_shutter_ns | Int | 40_000_000 | `>0` 才用，否则回默认 | `:133,375` |
| 3 | default_bitrate | Int | 10_000_000 | 不钳（档位表另给） | `:134,376` |
| 4 | default_ref_lines | Int | 0 | 位掩码直读 | `:231,416` |
| 5 | level_enabled | Bool | true | 直读 | `:232,417` |
| 6 | default_iso | Int | 100 | `>0` 才用 | `:162` |
| 7 | default_ae_mode | String(AeMode) | AUTO | `LOCK`/坏串一律归 `AUTO`（`.persisted`） | `:163` |
| 8 | default_ev | Int | 0 | 设备域由相机层钳 | `:164` |
| 9 | default_wb_preset | String(WbPreset) | AUTO | 坏串回 `AUTO` | `:165` |
| 10 | default_wb_kelvin | Int | 5500 | 域 2000..10000（相机层） | `:166` |
| 11 | default_wb_tint | Int | 0 | 域 −50..50 | `:167` |
| 12 | default_zoom | Float | 1.0 | `>0` 才用 | `:168` |
| 13 | default_flash | String(Flash) | OFF | 坏串回 `OFF` | `:169` |
| 14 | default_stabilize | String(Stabilize) | OFF | 坏串回 `OFF` | `:170` |
| 15 | default_af_mode | String(AfMode) | CONTINUOUS_VIDEO | 坏串回默认 | `:171` |
| 16 | default_mf_diopter | Float | 0 | `<0` 回 0 | `:172` |
| 17 | default_size | String(Size) | `1920x1080` | 坏串返回 `nil`（调用方跳过、保持出厂） | `:173` |
| 18 | default_frame_effect | String(FrameEffect) | NONE | 坏串回 `NONE` | `:174` |
| 19 | default_zebra_threshold | Float | 0.8 | 钳 0.6..1 | `:175` |
| 20 | default_zebra_density | Int | 2 | 钳 1..3 | `:176` |
| 21 | default_peaking_strength | Float | 0.6 | 钳 0..1 | `:177` |
| 22 | default_peaking_color | String(PeakingColor) | RED | 坏串回 `RED` | `:178` |
| 23 | default_lens | String(LensType) | WIDE | 坏串回 `WIDE`（产品枚举，非 cameraId） | `:179` |
| 24 | default_arc_merge_deadband | Int | 12 | 钳 0..32 | `:185,382` |
| 25 | denoise_audio | String(AudioDenoise) | `off` | 坏串回 `off`（用 **key** 字面量） | `:188` |
| 26 | denoise_video | String(VideoDenoise) | `off` | 坏串回 `off` | `:189` |
| 27 | correction_auto | Bool | true | 直读 | `:195` |
| 28 | correction_manual_ns | Int64 | 0 | 钳 0..8_000_000 | `:196` |
| 29 | default_marker_spread | Float | 0.60 | 钳 0.15..1.0 | `:204` |
| 30 | default_marker_color | String(MarkerLineColor) | YELLOW | 坏串回 `YELLOW` | `:205` |
| 31 | glow_params | String(复合串) | 关（缺键=出厂） | 坏串**整份**回默认 | `:218` |
| 32 | tone_params | String(复合串) | identity | 段数≠4 或全 0 → identity | `:300` |
| 33 | display_scale | String(DisplayScale) | ORIG | 坏串回 `ORIG` | `:211` |
| 34 | echo_params | String(复合串) | 关（缺键=出厂） | 坏串**整份**回默认 | `:224` |

> 刻意不入档（由 `WotaParamsPersistSession` 排除，见映射 `settings.md` 排除清单）：
> `renderMode`/`sampleRate`（设置页独占）、`audioEnabled`（音源开关）、`focusPoint`（瞬时点按态）、
> `curve`/`arc_convert_mode`（各有专属直写链）、`markerCount`（每次从 0 起步）。

### 3.1 复合串键编解码（`WotaSettingsCodecs`）

| 键 | 编码 | 容错口径 |
|---|---|---|
| glow_params | `enabled=0/1;threshold=;soft=;width=;gain=;add=;freq=;blend=;color=<UPPERHEX>;coloronly=0/1` | 写前 `sanitized()`；任何一段坏 → 整份回默认；`color`/`coloronly` 缺省可选 |
| echo_params | `enabled=;mode=<NAME>;multiplier=;threshold=;gain=;tcm=<NAME>;tc=<UPPERHEX>;ttol=` | 同上；`tcm`/`tc`/`ttol` 缺省可选 |
| tone_params | `black white shadows highlights`（空格分隔） | 段数≠4 → identity；四项绝对值和为 0 → identity |
| curve_stack（直写） | 4 段（master;red;green;blue），每段 `x:y`（`%.3f`，`en_US_POSIX`），按 x 排序 | 坏项丢弃；NaN/Inf 丢弃 |
| slider_continuous（直写） | `id,id,…`（`id ∈ shutter/iso/kelvin/tint/zoom/diopter/peaking`） | 空/坏串 → 空集；未知 id 忽略 |

> `hud_layout` 是 Android `HudLayoutTable`（CURRENT_VERSION=2，2000+ 行编解码器）的复合串。
> iOS 侧本任务**原样保留不透明串**（`hudLayoutRaw()`/`setHudLayoutRaw(_:)`：不丢、能原样回写），
> 结构化编辑归 UI 任务（G）。这是**有意的取舍**，不是遗漏。

## 4. 直写 UI 设置逐键（代表项 + 关键口径）

| 键 | 类型 | 默认 | 读取口径 | Android 源 |
|---|---|---|---|---|
| motion_mode | String(MotionMode) | FLUENT | 坏串回 `FLUENT` | `:131,358` |
| default_sample_rate | Int | 48000 | 直读 | `:135,377` |
| default_render_mode | String(RenderMode) | GPU | 只有 `DIRECT` 才算 DIRECT，其余 GPU | `:136,409` |
| default_audio_muted | Bool | false | 直读 | `:138,393` |
| ui_orientation | String(UIOrientation) | landscape | 小写 `persistValue`，解析**大小写不敏感** | `:249` |
| camera_brighten_delay_sec | Int | 1 | 钳 0..10 | `:272` |
| player_brighten_ramp_tenths | Int | 10 | 钳 0..30 | `:278` |
| player_top_bar_collapsed | Bool | false | 直读 | `:284` |
| hud_items | Int（位掩码） | `HudItem.defaultMask`=15 | 直读 | `:234,419` |
| hud_pills | Int（位掩码） | `CamPill.defaultMask`=全开−TONE−ECHO | 缺键=全开；版本落后 → 只补新位（纯读迁移） | `:236` |
| hud_pills_schema | Int | 0 | `>= CamPill.maskSchema(4)` 才不迁移 | `:243,430,968` |
| hud_layout | String | 缺键=nil | 不透明保留 | `:259` |
| hud_frost_blur | Bool | false | 直读 | `:326` |
| slider_continuous | String | 空集 | 见 §3.1 | `:309,435` |
| level_buzz_enabled | Bool | true | 直读 | `:233,418` |
| compare_tick_ms | Int | 200 | 钳 100..1000 | `:339,340` |
| text_scale_camera/settings/dialog | Int | 100 | 钳 80..120 | `:260-262` |
| gallery_columns | Int | 2 | 仅 `{2,3,4,5}`，否则回 2 | `:263` |
| curve_panel_side_dp | Float | 200 | **只钳下限**（NaN/<0→200；上限是组合期带 `maxSide` 二次钳） | `:292` |
| arc_convert_mode | String(ArcConvertMode) | MEND | 坏串回 `MEND` | `:316,367` |
| curve_stack | String | identity | 见 §3.1 | `:329` |
| diag_enabled / diag_verbose | Bool | false | 直读 | `:227,230,396,404` |

> `CamPill.migrateMask(mask, fromSchema:)` 是纯函数、幂等：只在 `fromSchema < maskSchema` 时
> OR 进「那一版之后新加」的位（`addedInSchema = {1: denoise, 2: glow}`；TONE/ECHO 刻意不在表里）。
> 写掩码时**必须同时写新 schema**（`setHudPillsMask` 内一起写），否则迁移会反复生效。

## 5. 备份（显式导出/导入；additive 不覆盖）

对齐 Android `backup/PortableUserData.kt`（`WotaBackupAgent` 的便携快照面）：

- 文件名 `wota_user_backup.bin`；`VERSION = 1`；`MAX_BYTES = 4 MiB`；`MAX_TAGS = 10000`；
  `MAX_STRING_BYTES = 65536`；标签值 ≤128 字。
- 键白名单 = `WotaSettingsKeys.backupKeys`（= Android `SETTING_KEYS` **去掉 6 个 `stance_*`**）。
- **二进制逐字节对齐 Android**（大端）：`VERSION(int32)`、`settings.size(int32)`、
  每键 `text(key) + byte(type) + value`（type 1=Bool/2=Int/3=Long/4=Float/5=String）、
  `tags.size(int32)`、每标签 `text(relativePath)+text(name)+int64(size)+int64(durationMs)+text(value)+int64(createdAt)`。
  `text = int32(len) + UTF-8 字节`。
- **大小/时长/时间戳用 Int64**（iOS 10 不引 `Codable`，也不假设 32 位整型位宽）。
- **加法恢复**：`settingsToRestore(restored:current:)` 只补目标当前**不存在**的键，
  绝不覆盖已存在值（**包含 `false` / `0`**）。
- 读入时允许设置条目上限用**跨端**口径 `maxSettingsCount = 64`（Android 白名单含 AI 键 = 63），
  这样 Android 生成的备份也能读；`stance_*` 在 `settingsOf` 阶段被剔除。
- 拒绝：坏版本、越界长度、截断、**尾随数据**（`atEnd`）、重复键。
- **无自动联网、无自动上传**：导出/导入都是显式本地调用（UI 走系统分享 / 文件选择器）。
- 标签（媒体指纹）结构保留并与 Android 互认（`MediaKey`/`mergeTags`/`uniqueCandidates`/`matches`），
  但当前无媒体库落地，导入的 tags 交回调用方（Library 任务处理）。

### 5.1 编解码语义核对（P1-01 修正后）

真源复核：`Android/.../backup/PortableUserData.kt` 的 `text()`（L176-184）是
`String.toByteArray(Charsets.UTF_8)` + `writeInt(byteCount)`，**不是** `DataOutputStream.writeUTF`
的 Java modified UTF-8（2 字节长度、U+0000 → `C0 80`、代理对分开编码）。因此 iOS 保持
**标准 UTF-8 + int32 字节长度**，`Data(value.utf8)` 与 Android 写出逐字节一致。

- **整数位型**：`writeInt` = 4 字节大端二补码，`writeLong` = 8 字节大端二补码，
  `writeFloat` = IEEE-754 32 位大端（与 Java `DataOutputStream` 一致）。修掉的两处是
  `UInt32` 位型未显式零扩展到 `UInt64`（`UserBackup.swift:353/361`）。
- **`Int` 上限**：线上类型是 Java 32 位 `int`；导出用 `Int32(exactly:)` 守卫，
  超域直接失败，不再 `truncatingIfNeeded` 静默改写位型。
- **控制字符**：`Unicode.Scalar` 没有 `isISOControl`；改用 `CharacterSet.controlCharacters`
  （Unicode 类别 `Cc`，与 `Character.isISOControl` 的 U+0000–U+001F / U+007F–U+009F 同义）。
- **标签值长度**：按 UTF-16 码元数（`String.utf16.count`）对齐 Java `String.length` 的 128 上限；
  `String.count` 是字素簇，会把 128 个 emoji 误判为 128 而 Android 读到 256 个码元整份拒收。
  空白判定用 Foundation 空白集合（略宽于 Java `isNotBlank`，只会更早丢弃标签，不会写出 Android 不接受的标签）。
  `MediaKey.isValid` 的 `relativePath`/`name` 同样改为空白判定，对齐 Android `isNotBlank()`。
- **解码严格性**：iOS 用严格 UTF-8（坏字节 → `invalidFormat`），Java 解码器则替换成 U+FFFD；
  安卓写出的备份永远是合法 UTF-8，互认不受影响，损坏输入在 iOS 侧整份拒绝。

### 5.2 文档 IO（P2-02 修正后）

`iOS/WotaGeiCam/Settings/UserBackupDocumentIO.swift`：

- **导入先限长**：`FileHandle.readData(ofLength: maxBytes + 1)`，峰值最多 `4 MiB + 1` 字节；
  不再有 `Data(contentsOf:)`。文件元数据 (`fileSize`) 只做快速拒绝，iCloud 占位/第三方 provider
  返回 0 或 nil 时仍由实际读取兜底，**不能只信 metadata**。
- **原子导出**：`Data.write(to:options:.atomic)`（同目录临时文件 + 原子替换），并去掉先
  `removeItem` 的旧写法，避免“旧文件已删、新文件未写”的空窗。
- **主线程**：`SettingsViewController` 已把导入放到后台串行队列，结果回主线程刷新表格并弹窗；
  读取安全作用域资源不占主线程。
- **入口核对**：设置页「备份与恢复」已有「导出备份快照 / 导入备份快照」两项：导出走系统分享
  （`UIActivityViewController`，用户主动点选才算同意分享），导入走系统文档选择器
  （`UIDocumentPickerViewController`，iOS 8+ 入口；iOS 11+ 用 `didPickDocumentsAt`，iOS 10 用
  `didPickDocumentAt`）。取消由系统控件自身完成，失败有可见弹窗，无任何自动联网。


## 6. 更新检查（手动；无自动下载/安装）

对齐 Android `update/UpdateChecker.kt` 的**纯逻辑层**，iOS 数据面落在 `WotaCore`，
网络真身在 `iOS/WotaGeiCam/Update/`。

| 段 | Android | iOS 落点 | 说明 |
|---|---|---|---|
| 段 1 | 直连 `api.github.com` latest | `WotaUpdateConfig.repoApiLatest` | 只有官方端点提取 Release 说明里的 SHA-256（可信） |
| 段 2 | 镜像前缀逐个代理同一 API | `WotaMirrorChain.probeChain()` | 全表 HTTPS；镜像 body 不可信（不提取哈希） |
| 段 3 | `releases/latest` 302 → tag | `WotaRedirectProbe.tagFromLocation` | 无 JSON 资产列表，资产名由 tag 推导 |

- **只认 `.ipa`**：`WotaReleaseJson.isSafeReleaseAssetName` 要求纯文件名 + `.ipa`，并显式拒绝
  `.apk` / `.hap`、`/`、`\`、`..`。**绝不选 Android/HarmonyOS 包或签名不明包**。
- **full/lite 选包**（与 Android 同构，仅后缀换成 `-iOS.ipa`）：
  - lite：只在 `-lite-` 资产里挑，`universal` 优先；无 → 不提示（保守）。
  - full：优先 `-full-` 资产（`universal` 优先），其次无 flavor 的历史通用包；**绝不推 lite 包**。
  - 资产名恒由 `WotaUpdateConfig.directDownloadUrl(tag:assetName:)` 用可信 tag + 已校验名重建，
    **不信 JSON 里的 URL 字段**（镜像可改写）。
- 版本比较 `WotaVersionCompare.isNewerAvailable`：去 `v` 前缀、主版本**恰好三段**数字、再比 `-rNN`；
  **任一侧解析失败 → 不提示（保守）**。本机 lite 版本名可带 `-lite` 后缀（仅本地剥离）。
- 说明清洗 `WotaReleaseNotes.forDisplay`：去标记、不重排；`SHA256*:` 行整行隐去；>4000 字截断。
- **外部签名/安装边界（硬事实）**：iOS **无** App 内 APK 式覆盖安装。本任务**只做**
  「查版本 + 用安全 HTTPS 页面打开」（releases/latest 或具体 tag 页 / 资产下载页）。
  unsigned IPA 必须由**外部工具重签并在真机安装**；App 内**不下载、不安装、不自动请求**。
  为外部重签后的完整性核验，另给 `UpdateChecker.verifyFile(at:expectedSha256:)`（纯本地、无联网；
  SHA-256 由 `WotaSHA256` 纯 Swift 实现，**不引 CryptoKit**）。

## 7. 接口契约（供 B1 相机 / G UI 接入）

### 7.1 `WotaCore`（纯逻辑，`Packages/WotaCore/Sources/WotaCore/`）

```
enum WotaSettingsKeys {
  static let prefsSuiteName = "wota_settings"
  static let paramsKeys: [String]        // 34，顺序即契约
  static let paramsKeySet: Set<String>
  static let backupKeys: Set<String>     // = Android SETTING_KEYS 去掉 6 个 AI 键（结构性剔除，不声明 AI 符号）
  // 全部键常量……
}

final class WotaSettingsStore {
  let defaults: UserDefaults
  init(defaults: UserDefaults)
  static func standard() -> WotaSettingsStore
  @discardableResult func flush() -> Bool            // defaults.synchronize()
  // 入档 getter：defaultFps/defaultShutterNs/defaultBitrate/defaultRefLines/levelEnabled/
  //   defaultIso/defaultAeMode/defaultEv/defaultWbPreset/defaultWbKelvin/defaultWbTint/defaultZoom/
  //   defaultFlash/defaultStabilize/defaultAfMode/defaultMfDiopter/defaultSize()->WotaVideoSize?/
  //   defaultFrameEffect/defaultZebraThreshold/defaultZebraDensity/defaultPeakingStrength/
  //   defaultPeakingColor/defaultLens/defaultArcMergeDeadband/denoiseAudio/denoiseVideo/
  //   correctionAuto/correctionManualNs/defaultMarkerSpread/defaultMarkerColor/
  //   glowParams()->GlowParams/toneParams()->ToneParams/displayScale/echoParams()->EchoParams
  // 入档 setter：setDefaultFps/…（与 getter 一一对应；只由 WotaParamsPersistSession 调用）
  // 直写 getter/setter：motionMode/defaultSampleRate/defaultRenderMode/defaultAudioMuted/
  //   uiOrientation/levelBuzzEnabled/hudItemsMask/hudPillsMask()/hudPillsSchema/hudLayoutRaw()/
  //   hudFrostBlur/continuousSliders()/compareTickMs/textScale*/galleryColumns/curvePanelSideDp()/
  //   arcConvertMode/curveStack()/cameraBrightenDelaySec/playerBrightenRampTenths/
  //   playerTopBarCollapsed/diagEnabled/diagVerbose
  func rawValue(forKey:) -> WotaBackupValue?
  func setRawValue(_ value: WotaBackupValue, forKey:)
}

struct WotaPersistedParams { … ; static let fieldCount = 34 }        // 冷启参数快照（仅 34 键语义）
enum WotaParamsRestore { static func load(from store:) -> WotaPersistedParams }
final class WotaParamsPersistSession {
  init(store: WotaSettingsStore, mount: WotaPersistedParams)   // mount = 进入录制页那一刻的总线快照（冻结影子）
  @discardableResult func flush(current: WotaPersistedParams) -> Bool   // 只写与影子不同的字段；无差异不写盘（返回 true）
}
```

> `WotaParamsPersistSession` 的语义：会话影子 diff 同时封死「静默覆盖设置页」缺陷——
> 用户在设置页改了某键、而录制页总线还停在旧值，此后任何 `flush` 都不会把陈旧值写回。
> 冷启流程：`WotaParamsRestore.load(from: store)` → 交给相机层套用；录制页在**动作完成点**
> 用 `session.flush(current:)` 写盘。

### 7.2 备份 / 更新（`WotaCore`）

```
enum WotaUserBackup {
  static let fileName/version/maxBytes/maxTags/maxStringBytes/maxTagValueChars/maxSettingsCount
  static func snapshot(from store: WotaSettingsStore, tags: [Tag] = []) -> Snapshot
  static func encode(_ snapshot: Snapshot) throws -> Data
  static func decode(_ data: Data) throws -> Snapshot
  @discardableResult static func importBackup(_ data: Data, into store: WotaSettingsStore) throws -> Int
  static func settingsOf(_:) / settingsToRestore(restored:current:) / mergeTags(existing:incoming:)
}

enum WotaSHA256 { static func hex(_ data: Data) -> String ; static func hash(_ data: Data) -> [UInt8] }

enum WotaUpdateProbe {
  static func probeLatestRelease(
    localVersionName: String,
    fetchJson: (String) -> String?,
    fetchRedirectLocation: (String) -> String?,
    isCancelled: () -> Bool = { false },
    flavor: String? = nil) -> WotaProbeResult
}
enum WotaProbeResult { case updateAvailable(latestTag:assetName:downloadUrl:sizeBytes:sha256:notes:) ; case upToDate ; case failed ; case cancelled }
```

### 7.3 App 层（`iOS/WotaGeiCam/`）

```
final class SettingsViewController: UITableViewController      // Settings/（系统控件；数据层消费示例）
enum UserBackupDocumentIO {                                     // Settings/
  static func exportToFile(store: WotaSettingsStore, tags: [WotaUserBackup.Tag] = []) -> URL?
  static func importFromFile(url: URL, into store: WotaSettingsStore) -> Int?
}
final class UpdateNetwork: NSObject, URLSessionTaskDelegate     // Update/（唯一网络实现）
final class UpdateChecker {                                     // Update/
  static let shared
  var localVersionName: String ; var flavor: String
  func check(completion: @escaping (WotaProbeResult) -> Void)   // 后台执行探测，回调在主线程
  func openReleasePage() ; func openTagPage(tag:) ; func openAssetDownloadPage(tag:assetName:)
  func verifyFile(at url: URL, expectedSha256: String) -> Bool  // 纯本地
}
```

## 8. 切割取舍（本任务做 / 不做）

- **做**：键清单、枚举与持久化口径、类型化 store、冷启 restore、影子 diff flush、复合串编解码、
  备份二进制编解码 + additive 恢复、更新纯逻辑（版本比较/资产挑选/说明清洗/镜像链/探测编排）、
  SHA-256（纯 Swift）、一个原生 `UITableViewController` 设置页、`Update/` 的 URLSession 网络实现。
- **不做（有意）**：`hud_layout` 的结构化编解码与拖放 UI（归 G）；库元数据/标签落库（归 D1）；
  更新包的 App 内下载/安装（iOS 无等价，归外部工具）；`AVCaptureSession` 侧的参数套用（归 B1）；
  权限状态区与「所有文件访问」区（映射 S-070/S-071，平台无等价，待裁决）。
- **不动**：`App/`、`Camera/`、`Library/`、`UI/`、`Config/`、`project.pbxproj`、`scripts/`、共享映射、
  `Android/`、`HarmonyOS/`；不提交、不推送。

## 9. 人工验证清单（真机，未执行）

- MV-F1-01 冷启恢复：改若干入档参数 → 杀进程 → 重启，参数保持；未入白名单项（如 markerCount）不持久。
- MV-F1-02 影子 diff：设置页改 A 键 → 进录制页做一次动作 → 退出；A 键**不被录制页陈旧值覆盖**。
- MV-F1-03 复合串：手填坏 `glow_params`/`echo_params` → 重启回出厂默认（整份），不崩。
- MV-F1-04 备份导出/导入：导出快照 → 卸载重装/换设备 → 导入；既有键（含 `false`/`0`）**不被覆盖**，
  缺失键被补齐；标签合并且不累积重复。
- MV-F1-05 跨端备份：Android 生成的 `wota_user_backup.bin` 能被 iOS 导入（`stance_*` 被忽略）。
- MV-F1-06 更新（本机已是最新）：点「检查更新」→ 提示已是最新；无后台请求（断网/无操作时无流量）。
- MV-F1-07 更新（有新版）：点「检查更新」→ 提示新版 tag；「打开发布页」在 Safari 打开 HTTPS 页；
  **无**自动下载/安装入口。
- MV-F1-08 选包：构造同时含 `.apk`/`.hap`/`.ipa` 的 releases JSON → 只挑 `.ipa`；lite 只挑 `-lite-`，
  full 不推 lite 包。
- MV-F1-09 SHA-256：对一份已知文件用 `WotaSHA256.hex` 比对标准测试向量一致。
- MV-F1-10 导入限长：在「文件」里放 4 MiB + 1 字节、200 MB 随机文件各一份 → 均提示导入失败，
  不崩溃、不明显卡顿、内存无数百 MB 峰值；正常 < 4 MiB 备份仍可导入。
- MV-F1-11 取消/失败可见：导入选择器点「取消」无副作用；选中损坏文件有失败弹窗；导出后分享面板
  可取消，未点选任何目标时不会产生网络/外发行为。

## 10. 验证状态（本机 Windows，无 Apple 工具链）

- 源码已落，**Apple 编译/归档未执行**：本机为 Windows，无 `xcodebuild`/`xcrun`/`swift`；
  不以结构检查替代编译，不声称「编译通过」。
- **未写测试、未跑模拟器、未截图**（用户指示）。
- 已做的静态核对：新增文件不含禁令牌（`import SwiftUI`/`Combine`/`@MainActor`/`UTType`/` await `/`Task {` 等，
  见 `scripts/check_project.py`）；不含 `stance` 实现符号；WotaCore 新增文件不引第三方依赖。
- 本轮（F1 修正）额外静态核对：`UserBackup.swift` 不再含 `isISOControl`、`UInt32`→`UInt64`
  隐式拓宽或 `Int32(truncatingIfNeeded:)`；`UserBackupDocumentIO.swift` 导入路径不再含
  `Data(contentsOf:)`，有 `readData(ofLength:)` 与 `options: .atomic`；`SettingsViewController` 导入在
  `importQueue.async` 执行、UI 回 `DispatchQueue.main.async`。仍**未**做 Apple 编译、未跑模拟器/测试。
- 工程侧：新增 `WotaGeiCam/Settings/*.swift`、`WotaGeiCam/Update/*.swift`、
  `Packages/WotaCore/Sources/WotaCore/{Settings*,Update*,UserBackup*}.swift` 需在**生成工程时纳入 Sources**
  （`scripts/generate_project.py` 自动发现），由主代理重跑生成脚本，不在本任务写入工程文件。
  > 注意：在重跑生成脚本前，`check_project.py` 会因「新 Swift 文件不在 pbxproj Sources」而报错——这是**预期**，
  > 不是源码缺陷。
