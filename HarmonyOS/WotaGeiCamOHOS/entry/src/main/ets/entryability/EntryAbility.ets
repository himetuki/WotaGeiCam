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

import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { Diag } from '../diag/Diag';
import { PureLogicBarrel } from '../player/PureLogicBarrel';
import { ArcRepairRunner } from '../record/ArcRepairRunner';
import { WindowChrome } from '../ui/WindowChrome';
import { SettingsStore } from '../ui/SettingsStore';

const DOMAIN = 0x0000;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    // 装诊断日志（必须在写第一条日志之前；重复调用只更新开关）——hilog 通道恒活，文件侧走开关门
    Diag.install(this.context);
    // T25 P3-1：编译守卫——把 T24 纯逻辑模块拉进 ArkTS program 并留一行启动签名
    Diag.i('WotaPure', `pure logic barrel ${PureLogicBarrel.signature()}`);
    // T29 编译门：离线修复门面（fd 生命周期/轮询/归因）必须进 ArkTS program
    Diag.i('WotaRepair', `repair contract ${ArcRepairRunner.contract()}`);
    try {
      // T31：强制深色——相机 UI 是深色 HUD，系统组件（按钮/系统栏/弹层）跟随深色，
      // 避免真机取证里的「蓝按钮 + 白系统栏 + 绿录制键」浅色混入。
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    } catch (err) {
      hilog.error(DOMAIN, 'testTag', 'Failed to set colorMode. Cause: %{public}s', JSON.stringify(err));
    }
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onCreate');
  }

  onDestroy(): void {
    // 会话收尾留痕：下次冷启的「上一会话结束于」能区分"被杀"与"正常退出"
    Diag.markCleanExit();
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // Main window is created, set main page for this ability
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageCreate');

    // T31 复审 P3-5：loadContent 之前先预置「全屏 + 透明系统栏 + 黑底」，消除冷启动首帧白条；
    // 页面侧 applyCamera/applyStandardDark 经同一 Promise 链串行，快速切页不会乱序。
    try {
      WindowChrome.prime(windowStage.getMainWindowSync());
    } catch (err) {
      hilog.warn(DOMAIN, 'testTag', 'Failed to prime window chrome. Cause: %{public}s', JSON.stringify(err));
    }

    // UO 批：冷启落界面方向（窗口级 sticky，全页生效；坏串/缺键回竖屏固定）。
    // 在 prime 之后的串行链里执行，避免与全屏/系统栏设置竞态。
    try {
      const ctx = this.context;
      WindowChrome.applyOrientation(windowStage.getMainWindowSync(), SettingsStore.uiOrientationSync(ctx));
    } catch (err) {
      hilog.warn(DOMAIN, 'testTag', 'Failed to apply UI orientation. Cause: %{public}s', JSON.stringify(err));
    }

    windowStage.loadContent('pages/Camera', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, 'testTag', 'Succeeded in loading the content.');
    });
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onForeground');
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onBackground');
  }
}