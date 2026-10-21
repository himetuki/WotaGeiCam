/**
 * GL 相机试验页（GL-1）：XComponent(SURFACE, libraryname='entry') 的原生面由 cpp 侧
 * OH_NativeImage 消费相机帧（external OES → 上游直通着色器上屏）。
 *
 * 加载顺序（P2，顺序风险修复后的契约）：
 * - **不在页面顶层静态 import 'libentry.so'**：静态 import 会让原生模块先于 XComponent 加载、
 *   以「无 OH_NATIVE_XCOMPONENT_OBJ 的 exports」完成 Init 并进模块缓存，之后 XComponent 创建时
 *   Init 不再重跑，surface 回调就永远不会注册；
 * - 改为 XComponent onLoad 之后再 dynamic import：此时 XComponent 的加载路径已带着
 *   OH_NATIVE_XCOMPONENT_OBJ 跑完 Init（注册 surface/OnFrame 回调），本页拿到的只是同一模块缓存实例；
 * - 首进/重进页均由 XComponent 触发加载 → Init 注册回调 → Created → WotaGlInit 建 NativeImage →
 *   onLoad 后本页轮询 getInputSurfaceId（100ms × 5s）。
 * 已知边界：旋转/镜像未校正（buildTexCoords 留 GL-2）；独立渲染线程留白。
 */
import { common } from '@kit.AbilityKit';
import { GlCameraEngine } from '../camera/GlCameraEngine';
import { requestCameraPermission } from '../camera/PermGate';

/** libentry.so 的本地视图（types/libentry/index.d.ts 的运行时对应） */
interface GlLibModule {
  getInputSurfaceId: () => string;
  getNativeImageError: () => string;
}

const POLL_INTERVAL_MS: number = 100;
const POLL_TIMEOUT_MS: number = 5000;

@Entry
@Component
struct GlPreview {
  @State message: string = 'waiting for GL surface...';
  private engine: GlCameraEngine = new GlCameraEngine();
  private pollTimer: number = -1;
  private glLib: GlLibModule | undefined = undefined;

  aboutToDisappear(): void {
    this.stopPolling();
    this.engine.stop();
  }

  /** XComponent onLoad 之后再加载原生模块（见文件头「加载顺序」） */
  private loadNativeModule(): void {
    // 注：SDK 对 .so 导入恒报一条「napi module 未验证」提示（28014）。实测 // @ts-ignore
    // 会被 ArkTS 以 arkts-strict-typing-required（10605146）硬拒、构建红；// @ts-nocheck 会
    // 牺牲整文件类型检查，不可取——保留该提示为已知工具链项（登记 PORT_MAP）。
    import('libentry.so').then((mod: ESObject): void => {
      this.glLib = mod as GlLibModule;
      this.startPolling();
    }).catch((e: Object): void => {
      this.message = `native module load failed: ${e}`;
    });
  }

  /** onLoad 时 surface 已创建，但 cpp 侧 NativeImage 可能还没建好 → 轮询 surfaceId */
  private startPolling(): void {
    this.stopPolling();
    const startMs: number = Date.now();
    this.pollTimer = setInterval((): void => {
      const lib: GlLibModule | undefined = this.glLib;
      if (lib === undefined) {
        return;
      }
      let surfaceId: string = '';
      try {
        surfaceId = lib.getInputSurfaceId();
      } catch (e) {
        surfaceId = '';
      }
      if (surfaceId !== '') {
        this.stopPolling();
        this.message = `native surface: ${surfaceId}`;
        this.startCamera(surfaceId);
        return;
      }
      // NativeImage 明确失败（create/listener/surfaceId）→ 立即结束轮询并显示原因
      let nativeError: string = '';
      try {
        nativeError = lib.getNativeImageError();
      } catch (e) {
        nativeError = '';
      }
      if (nativeError !== '') {
        this.stopPolling();
        this.message = `GL native image failed: ${nativeError}`;
        return;
      }
      if (Date.now() - startMs > POLL_TIMEOUT_MS) {
        this.stopPolling();
        this.message = 'GL surface not created (timeout: no native image yet)';
      }
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private startCamera(nativeSurfaceId: string): void {
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx === undefined) {
      this.message = 'no host context';
      return;
    }
    requestCameraPermission(ctx).then((granted: boolean): void => {
      if (!granted) {
        this.message = 'camera permission denied';
        return;
      }
      this.engine.start(ctx, nativeSurfaceId).then((summary: string): void => {
        this.message = summary;
      });
    });
  }

  build() {
    Stack() {
      // libraryname 与 cpp/CMakeLists.txt 的 add_library(entry ...) 及 napi nm_modname 三者一致
      XComponent({ id: 'wotaGlCamera', type: XComponentType.SURFACE, libraryname: 'entry' })
        .onLoad(() => {
          this.loadNativeModule();
        })
        .height('100%')
        .width('100%')

      Column({ space: 12 }) {
        Text(this.message)
          .fontSize(12)
          .fontColor(Color.White)
          .maxLines(3)
          .textAlign(TextAlign.Center)
        Button('返回')
          .fontSize(14)
          .onClick(() => {
            this.getUIContext().getRouter().back();
          })
      }
      .alignItems(HorizontalAlign.Center)
      .width('100%')
      .padding(16)
    }
    .height('100%')
    .width('100%')
    .backgroundColor(Color.Black)
  }
}
