/**
 * GL 试验页（GL-0）：XComponent(SURFACE, libraryname='entry') 承载原生 EGL/GLES3 渲染，
 * 原生侧在 OnFrame（vsync）回调里画全屏三角形动画——只证明「原生库装载 → surface 生命周期 →
 * program/uniform/swap」管线可跑通；上游着色器族从 GL-1 起移植。
 * 相机帧进 GL（NativeImage/SurfaceTexture 等价）不在本批（登记 GL-1）。
 */

@Entry
@Component
struct GlPreview {
  build() {
    Stack() {
      // libraryname 与 cpp/CMakeLists.txt 的 add_library(entry ...) 及 napi nm_modname 三者一致
      XComponent({ id: 'wotaGlTest', type: XComponentType.SURFACE, libraryname: 'entry' })
        .height('100%')
        .width('100%')

      Column({ space: 12 }) {
        Text('GL bring-up (GL-0)')
          .fontSize(16)
          .fontColor(Color.White)
        Button('返回')
          .fontSize(14)
          .onClick(() => {
            // 用 UIContext.getRouter()（模块级 router.back 自 API 18 弃用）
            this.getUIContext().getRouter().back();
          })
      }
      .alignItems(HorizontalAlign.Center)
      .padding(16)
    }
    .height('100%')
    .width('100%')
    .backgroundColor(Color.Black)
  }
}
