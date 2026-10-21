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
import { WotaParams } from '../core/CoreAnchors';
// 探测版适配层的页面真实消费者：点击即 getCameraManager → 枚举镜头 → dump（camera/KitProbe.ets）。
// KitProbe 传递引用 KitLens/KitAbility/VideoSizeKit，探测适配层四个文件均在编译图内。
import { probeAndDump } from '../camera/KitProbe';
// 预览批：CAMERA 权限申请门 + PhotoSession 预览引擎（PermGate/KitPreview 又把两文件拉进编译图）。
import { requestCameraPermission } from '../camera/PermGate';
import { PreviewEngine } from '../camera/KitPreview';
// diag 完整版的页面真实消费者（开关/导出/清除三按钮）
import { Diag, ExportResult } from '../diag/Diag';
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

  aboutToAppear(): void {
    // 反向初始化开关态：页面重建后按钮文案与实际总开关一致（Diag 状态活在进程内，持久化留白）
    this.logEnabled = Diag.isEnabled();
    this.refreshDiagSummary();
  }

  aboutToDisappear(): void {
    // 关页收尾：stop 逐项清理不抛；fire-and-forget（页面生命周期内完成主要清理即可）
    this.engine.stop();
  }

  private refreshDiagSummary(): void {
    try {
      this.diagSummary = `${Diag.workingDir()} · ${Diag.usedBytes()}B`;
    } catch (e) {
      this.diagSummary = '';
    }
  }

  build() {
    Stack() {
      // 预览面（SURFACE）：onLoad 时 surface 已创建，先存 id；会话在权限通过后再开
      XComponent({ type: XComponentType.SURFACE, controller: this.xhc })
        .onLoad(() => {
          this.surfaceId = this.xhc.getXComponentSurfaceId();
        })
        .height('100%')
        .width('100%')

      Column({ space: 12 }) {
        Text(this.message)
          .id('HelloWorld')
          .fontSize($r('app.float.page_text_font_size'))
          .fontWeight(FontWeight.Bold)
          .fontColor(Color.White)
          .maxLines(8)
          .textAlign(TextAlign.Center)

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
      .alignItems(HorizontalAlign.Center)
      .width('100%')
      .padding(16)
    }
    .height('100%')
    .width('100%')
  }
}
