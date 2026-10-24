/*
 * Copyright (c) 2026 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 移植编译图锚点：把 record 纯逻辑锚点模块拉进 CompileArkTS 检查（未被入口引用的 .ets 不参与编译检查，构建会假绿）。
// 符号本身未被页面使用；录制引擎批次落地后移除（见 record/RecordAnchors.ets 头注释）。
import { ArcKeepRule } from '../record/RecordAnchors';
// 同上：core 直译批次七个文件的编译图锚点（见 core/CoreAnchors.ets 头注释）。
// WotaParams/ParamState 在本页参数自检里成为真实消费者（参数总线）。
import { ParamState, WotaParams } from '../core/CoreAnchors';
import { Flash, WbPreset } from '../core/Constants';
// 探测版适配层的页面真实消费者：点击即 getCameraManager → 枚举镜头 → dump（camera/KitProbe.ets）。
// KitProbe 传递引用 KitLens/KitAbility/VideoSizeKit，探测适配层四个文件均在编译图内。
import { probeAndDump } from '../camera/KitProbe';
// 预览批：CAMERA 权限申请门 + PhotoSession 预览引擎（PermGate/KitPreview 又把两文件拉进编译图）。
import { requestCameraPermission } from '../camera/PermGate';
import { PreviewEngine } from '../camera/KitPreview';
// diag 完整版的页面真实消费者（开关/导出/清除三按钮）
import { Diag, ExportResult } from '../diag/Diag';
// 录制批真实消费者：录制引擎 + 双权限申请 + 结果契约（KitRecorder 传递引用 VideoStore/RecordProfile）
import { RecordingEngine } from '../camera/KitRecorder';
import { RecordPermResult, requestRecordPermissions } from '../camera/PermGate';
import { RecordResult } from '../record/RecordResult';
// T27-1 平台探针：AVScreenCapture 内录取证（真机批第一步；Runner 传递引用 AvScreenProbeModel）
import { cancelAvScreenProbe, runAvScreenProbe } from '../record/AvScreenProbeRunner';
import { common } from '@kit.AbilityKit';

@Entry
@Component
struct Index {
  @State message: string = 'Hello World';
  @State surfaceId: string = '';
  @State logEnabled: boolean = false;
  @State diagSummary: string = '';
  private xhc: XComponentController = new XComponentController();
  private engine: PreviewEngine = new PreviewEngine();
  /** 录制引擎（独立 VideoSession；起录前停预览、停录后恢复预览） */
  private recorder: RecordingEngine = new RecordingEngine();
  @State recording: boolean = false;
  /** 录制按钮防重复：权限/起录/停录异步链进行中禁用按钮 */
  @State recordingBusy: boolean = false;
  /** T27-1 探针：取证进行中（按钮防重入） */
  @State avProbeBusy: boolean = false;
  /** 参数总线：参数自检八档的输入（也是后续 HUD 的同一实例来源） */
  private params: WotaParams = new WotaParams();

  aboutToAppear(): void {
    // 反向初始化开关态：页面重建后按钮文案与实际总开关一致（Diag 状态活在进程内，持久化留白）
    this.logEnabled = Diag.isEnabled();
    this.refreshDiagSummary();
  }

  aboutToDisappear(): void {
    // 探针后台窗口随页面销毁收口（native 侧窗口上限是硬兜底）
    cancelAvScreenProbe();
    // 先停录制再停预览（录制占用相机；recorder.stop 幂等，未录制返回 BAD_STATE 无副作用）
    this.recorder.stop().then((): void => {
      this.engine.stop();
    });
  }

  private refreshDiagSummary(): void {
    try {
      this.diagSummary = `${Diag.workingDir()} · ${Diag.usedBytes()}B`;
    } catch (e) {
      this.diagSummary = '';
    }
  }

  build() {
    Stack({ alignContent: Alignment.Top }) {
      // 预览面（SURFACE）：onLoad 时 surface 已创建，先存 id；会话在权限通过后再开
      XComponent({ type: XComponentType.SURFACE, controller: this.xhc })
        .onLoad(() => {
          this.surfaceId = this.xhc.getXComponentSurfaceId();
        })
        .height('100%')
        .width('100%')

      Column({ space: 12 }) {
        // T31：开发者页补页头 + 返回（原页无导航入口、长文案 32fp 盖屏）
        Row({ space: 8 }) {
          Button('返回')
            .fontSize(13)
            .height(48)
            .borderRadius(24)
            .backgroundColor('#B312171C')
            .onClick((): void => {
              this.engine.stop();
              this.getUIContext().getRouter().back();
            })
          Text('开发者：Index（AVRecorder 试验）')
            .fontSize(17)
            .fontColor(Color.White)
            .layoutWeight(1)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('100%')

        // 日志区：13fp + 固定高度可滚动（原实现 32fp 铺满整屏）
        Scroll() {
          Text(this.message)
            .id('HelloWorld')
            .fontSize(13)
            .fontColor(Color.White)
            .maxLines(30)
            .textAlign(TextAlign.Start)
            .width('100%')
        }
        .constraintSize({ maxHeight: 220 })
        .width('100%')
        .scrollBar(BarState.Off)

        Row({ space: 12 }) {
          Button('探测')
            .onClick(() => {
              // 回归锚点：探测版路径（无会话、无权限依赖，走 probeAndDump 内部兜底）
              const ctx: common.Context | undefined =
                this.getUIContext().getHostContext() as common.Context | undefined;
              if (ctx === undefined) {
                this.message = 'no host context';
                return;
              }
              this.message = probeAndDump(ctx);
            })
          Button('预览')
            .onClick(() => {
              // 权限 → 会话：拒绝时兜底提示、可重点重试；surface 未就绪时提示重进页面
              const ctx: common.Context | undefined =
                this.getUIContext().getHostContext() as common.Context | undefined;
              if (ctx === undefined) {
                this.message = 'no host context';
                return;
              }
              if (this.surfaceId === '') {
                this.message = 'preview pending: surface not ready, reopen page';
                return;
              }
              this.message = 'requesting camera permission...';
              requestCameraPermission(ctx).then((granted: boolean): void => {
                if (!granted) {
                  this.message = 'camera permission denied (tap to retry)';
                  return;
                }
                // viewW/H 传 0：onLoad 时未布局，core pickDirectPreviewSize 自有取最大档的防御分支
                this.engine.start(ctx, this.surfaceId, 0, 0)
                  .then((summary: string): void => {
                    this.message = summary;
                  });
              });
            })
          Button(this.recording ? '停录' : '录制')
            .fontSize(14)
            .enabled(!this.recordingBusy)
            .onClick(() => {
              const ctx: common.Context | undefined =
                this.getUIContext().getHostContext() as common.Context | undefined;
              if (ctx === undefined) {
                this.message = 'no host context';
                return;
              }
              if (this.surfaceId === '') {
                this.message = 'preview pending: surface not ready, reopen page';
                return;
              }
              if (!this.recording) {
                // 起录：双权限 → 停预览 → 独立 VideoSession 录制；mic 拒绝退视频-only
                this.recordingBusy = true;
                requestRecordPermissions(ctx).then((perm: RecordPermResult): void => {
                  if (!perm.camera) {
                    this.message = 'camera permission denied (tap to retry)';
                    this.recordingBusy = false;
                    return;
                  }
                  this.message = 'starting recording...';
                  this.engine.stop().then((): void => {
                    this.recorder.start(ctx, this.surfaceId, this.params, perm.mic)
                      .then((summary: string): void => {
                        this.recording = this.recorder.isRecording();
                        const micNote: string = perm.mic ? '' : '\n(mic denied: video-only)';
                        if (!this.recording) {
                          // 起录失败：恢复预览（失败仅提示）
                          this.message = `${summary}${micNote}`;
                          this.engine.start(ctx, this.surfaceId, 0, 0)
                            .then((s: string): void => {
                              this.message += `\n${s}`;
                              this.recordingBusy = false;
                            });
                        } else {
                          this.message = `${summary}${micNote}`;
                          this.recordingBusy = false;
                        }
                      });
                  });
                });
              } else {
                // 停录：结束录制 → 显示结果 → 恢复预览
                this.recordingBusy = true;
                this.recorder.stop().then((res: RecordResult): void => {
                  this.recording = false;
                  this.message = res.summary();
                  this.engine.start(ctx, this.surfaceId, 0, 0)
                    .then((s: string): void => {
                      this.message += `\n${s}`;
                      this.recordingBusy = false;
                    });
                });
              }
            })
          Button('GL 试验')
            .fontSize(14)
            .onClick(() => {
              // GL-0 试验页：原生 EGL/GLES3 管线验证（相机预览/录制链路不受影响）
              // 用 UIContext.getRouter()（模块级 router.pushUrl 自 API 18 弃用）
              this.getUIContext().getRouter().pushUrl({ url: 'pages/GlPreview' });
            })
          Button('媒体库')
            .fontSize(14)
            .onClick(() => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' });
            })
          Button('参数自检')
            .fontSize(14)
            .onClick(() => {
              try {
                const lines: Array<string> = [];
                this.params.zoom.value = ParamState.of(1);
                lines.push(`①zoom=1 ${this.engine.applyParams(this.params)}`);
                this.params.zoom.value = ParamState.of(2);
                lines.push(`②zoom=2 ${this.engine.applyParams(this.params)}`);
                this.params.ev.value = ParamState.of(-2);
                lines.push(`③ev=-2 ${this.engine.applyParams(this.params)}`);
                this.params.ev.value = ParamState.of(2);
                lines.push(`④ev=+2 ${this.engine.applyParams(this.params)}`);
                this.params.wbMode.value = WbPreset.DAYLIGHT;
                lines.push(`⑤wb=DAYLIGHT ${this.engine.applyParams(this.params)}`);
                this.params.wbMode.value = WbPreset.CLOUDY_DAYLIGHT;
                lines.push(`⑥wb=CLOUDY ${this.engine.applyParams(this.params)}`);
                this.params.flash.value = Flash.TORCH;
                lines.push(`⑦flash=TORCH ${this.engine.applyParams(this.params)}`);
                this.params.flash.value = Flash.OFF;
                lines.push(`⑧flash=OFF ${this.engine.applyParams(this.params)}`);
                this.message = lines.join('\n');
              } catch (e) {
                this.message = `param self-check failed: ${e}`;
              }
            })
          Button('AVScreenCapture 探针')
            .fontSize(14)
            .enabled(!this.avProbeBusy)
            .onClick(() => {
              this.avProbeBusy = true;
              this.message = '探针运行中（约 2.5s；期间可能弹系统确认框——弹窗形态正是取证目标，请照常操作）…';
              runAvScreenProbe().then((info): void => {
                this.avProbeBusy = false;
                this.message = info.summary();
              }).catch((e: Object): void => {
                this.avProbeBusy = false;
                this.message = `probe failed: ${e}`;
              });
            })
        }

        // 诊断区：日志开关 / 导出 / 清除（diag 完整版的最小消费面；预览/探测路径不动）
        Row({ space: 8 }) {
          Button(this.logEnabled ? '日志关' : '日志开')
            .fontSize(14)
            .onClick(() => {
              try {
                Diag.setEnabled(!this.logEnabled);
                this.logEnabled = !this.logEnabled;
                this.refreshDiagSummary();
              } catch (e) {
                this.message = `diag toggle failed: ${e}`;
              }
            })
          Button('导出日志')
            .fontSize(14)
            .onClick(() => {
              const ctx: common.Context | undefined =
                this.getUIContext().getHostContext() as common.Context | undefined;
              if (ctx === undefined) {
                this.message = 'no host context';
                return;
              }
              Diag.export(ctx)
                .then((res: ExportResult | null): void => {
                  this.message = res === null
                    ? '日志导出失败（见 hilog WotaDiag）'
                    : `日志已导出\n${res.path}\nbytes=${res.bytes}`;
                  this.refreshDiagSummary();
                })
                .catch((e: Object): void => {
                  this.message = `diag export failed: ${e}`;
                });
            })
          Button('清除日志')
            .fontSize(14)
            .onClick(() => {
              Diag.clear()
                .then((): void => {
                  this.refreshDiagSummary();
                  this.message = '日志已清除';
                })
                .catch((e: Object): void => {
                  this.message = `diag clear failed: ${e}`;
                });
            })
        }

        Text(this.diagSummary)
          .fontSize(12)
          .fontColor(Color.Gray)
          .maxLines(2)
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(16)
    }
    .height('100%')
    .width('100%')
  }
}
