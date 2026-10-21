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
import { common } from '@kit.AbilityKit';

@Entry
@Component
struct Index {
  @State message: string = 'Hello World';

  build() {
    RelativeContainer() {
      Text(this.message)
        .id('HelloWorld')
        .fontSize($r('app.float.page_text_font_size'))
        .fontWeight(FontWeight.Bold)
        .alignRules({
          center: { anchor: '__container__', align: VerticalAlign.Center },
          middle: { anchor: '__container__', align: HorizontalAlign.Center }
        })
        .onClick(() => {
          // 探测版：无 CAMERA 权限时 probeAndDump 内部走 catch 兜底，页面显示错误摘要（预期行为）
          const ctx: common.Context | undefined = this.getUIContext().getHostContext() as common.Context | undefined;
          if (ctx === undefined) {
            this.message = 'no host context';
            return;
          }
          this.message = probeAndDump(ctx);
        })
    }
    .height('100%')
    .width('100%')
  }
}