# WotaGeiCam 安卓图标资源包

深空蓝渐变底板 + 相机镜头光圈 + 红色 REC 点 + 荧光棒挥动光轨，呼应 WOTA 艺 24/25fps、慢快门光轨拍摄场景。

## 目录结构

```
WotaGeiCam_Android_Icons/
├── README.md
├── play_store/
│   ├── ic_launcher_playstore.png      512×512 不透明（Google Play 上架专用）
│   └── ic_launcher_source_1024.png    1024×1024 不透明（源文件/应用商店大图）
└── app/src/main/res/
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml            自适应图标（方）
    │   └── ic_launcher_round.xml      自适应图标（圆）
    ├── drawable/
    │   └── ic_launcher_background.xml 背景层：深空蓝渐变 #0B1A33 → #040A16
    ├── drawable-nodpi/
    │   └── ic_launcher_monochrome.png 单色层（Android 13+ 主题图标）
    ├── mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
    │   ├── ic_launcher_foreground.png 前景层 108/162/216/324/432
    │   ├── ic_launcher.png            传统方形图标 48/72/96/144/192
    │   └── ic_launcher_round.png      传统圆形图标 48/72/96/144/192
    └── drawable-{mdpi..xxxhdpi}/
        └── ic_stat_rec.png            通知栏白色剪影小图标 24dp
```

## 接入方式

1. 把 `app/src/main/res/` 下的目录整体合并进工程 `WotaGeiCamApp/app/src/main/res/`（同名目录直接合并，不要整包覆盖，避免覆盖已有的 values/colors.xml）。
2. 确认 `AndroidManifest.xml` 中 application 节点：

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ... >
```

3. minSdk 29 已 ≥ 26，自适应图标生效；`mipmap-*` 里的 `ic_launcher.png` 仅作部分 ROM / 快捷方式的兜底。
4. 通知小图标用法：`NotificationCompat.Builder(...).setSmallIcon(R.drawable.ic_stat_rec)`。

## 规范说明

- 前景层主体严格居中并填满 66dp 安全区（432px 画布内为 264px），任意 OEM 遮罩（圆/方/泪滴/ squircle）裁切都不会切到主体。
- 背景层用渐变 shape，避免 OEM 深色/浅色主题下突兀；Android 13 主题图标走 `monochrome` 层，由系统着色。
- 全部图标含 alpha 通道，未做有损压缩，可用 pngquant/ImageOptim 再压一轮减小包体。

## 需要重新出图时

改配色只需替换 `drawable/ic_launcher_background.xml` 的两个色值并重跑合成脚本；改主体符号需重新生成前景层。

## 去水印与接入记录（2026-10-09）

- 本包 23 张图原先右下角都带「元宝 AI生成」水印（含 1024 源图、512 商店图、各密度派生图与通知剪影）——**已全部去除**（提交 `6fba79e`）。手法：启动图标家族的水印相对位置一致（整包是同一张 1024 源的等比缩放），在源图定框后按比例折算；通知剪影 `ic_stat_rec` 另有一套几何（更靠角上），单独按"只扫 ≥75% 角"的窄口径处理。透明底（前景层 / 单色层 / 剪影）把水印像素清成全透明，渐变底（合成图 / 商店图）按行做水平线性插值补底。
- 验收方式：修好图与备份**逐像素对比**，确认改动像素**全部落在水印框内、框外零改动**（3901 像素 / 22 张）——图形本体未被触碰。去水印前的原图备份在仓库外的 `.tmp/icon/orig/`（不进 git）。
- 已接入工程 `WotaGeiCamApp/app/src/main/res/`：`AndroidManifest` 的 `android:icon=@mipmap/ic_launcher` 与 `android:roundIcon=@mipmap/ic_launcher_round`，前台服务通知小图标用 `R.drawable.ic_stat_rec`。
- ⚠ **重新生成素材时请先去掉水印再用**：若拿带水印的新图直接覆盖，水印会随图进包（尤其通知剪影里也会有一小片）。
