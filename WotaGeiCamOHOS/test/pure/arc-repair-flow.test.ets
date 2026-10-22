/**
 * 流式执行桥（record/ArcRepairFlow.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ArcRepairPlanTest.kt 的桥测试面（对齐上游 38d712a）。
 *
 * 9 条：45@30→24 发 36 帧、PTS 均匀严格递增、Hold/Merge/Emit 计数与 EOS 清场、被抽帧
 * 合并计数对账、60→24 连续抽帧不 fresh、EOS 尾巴、首保留帧前丢弃 + 空流、45 帧逐帧不变量
 * 枚举、NTSC 721 帧末 PTS。期望值一律手写字面量。
 */
import { ArcOp, ArcOpKind, ArcRepairFlow } from '../../entry/src/main/ets/record/ArcRepairFlow';
import { ArcRepairPlan } from '../../entry/src/main/ets/record/ArcRepairPlan';

/** 按 30→24/N=45 完整喂一遍流，返回 (emitted pts 列表, 全部指令) */
function feed30to24(): Array<Array<ArcOp>> {
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(45, 30, 1, 24));
  const ops: Array<ArcOp> = [];
  for (let s: number = 0; s < 45; s++) {
    const batch: Array<ArcOp> = flow.onSourceFrame(s);
    for (const op of batch) {
      ops.push(op);
    }
  }
  const tail: Array<ArcOp> = flow.onSourceEos();
  for (const op of tail) {
    ops.push(op);
  }
  return [ops, tail];
}

function countKind(ops: Array<ArcOp>, kind: ArcOpKind): number {
  let n: number = 0;
  for (const op of ops) {
    if (op.kind === kind) {
      n++;
    }
  }
  return n;
}

function ptsOf(ops: Array<ArcOp>): Array<number> {
  const pts: Array<number> = [];
  for (const op of ops) {
    if (op.kind === ArcOpKind.EMIT) {
      pts.push(op.ptsUs);
    }
  }
  return pts;
}

test('桥 发出帧数等于计划帧数', (): void => {
  const result: Array<Array<ArcOp>> = feed30to24();
  const pts: Array<number> = ptsOf(result[0]);
  assertEq(ArcRepairPlan.dstFrameCount(45, 30, 1, 24), pts.length, '与计划帧数一致');
  assertEq(36, pts.length, '45@30→24 发 36 帧');
});

test('桥 PTS为均匀目标帧率步长 严格递增', (): void => {
  const pts: Array<number> = ptsOf(feed30to24()[0]);
  for (let j: number = 0; j < pts.length; j++) {
    assertEq(Math.floor(j * 1000000 / 24), pts[j], `第 ${j} 帧 PTS`);
  }
  for (let j: number = 1; j < pts.length; j++) {
    assertTrue(pts[j] > pts[j - 1], `PTS 严格递增 @${j}`);
  }
});

test('桥 滞留帧恰好发出一次且EOS后清零', (): void => {
  const ops: Array<ArcOp> = feed30to24()[0];
  const holds: number = countKind(ops, ArcOpKind.HOLD);
  const merges: number = countKind(ops, ArcOpKind.MERGE);
  const emits: number = countKind(ops, ArcOpKind.EMIT);
  // 首枚保留帧 Hold 一次；其后每枚保留帧经 Merge 升格；Hold+Merge == Emit == 36
  assertEq(1, holds, 'Hold 一次');
  assertEq(35, merges, 'Merge 35 次');
  assertEq(emits, holds + merges, 'Hold+Merge == Emit');
  assertEq(36, emits, 'Emit 36');
  // EOS 后状态机清零：再问一次只会得到空指令
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(45, 30, 1, 24));
  for (let s: number = 0; s < 45; s++) {
    flow.onSourceFrame(s);
  }
  flow.onSourceEos();
  assertEq(0, flow.onSourceEos().length, 'EOS 后再问为空');
  assertEq(0, flow.keptCount - 36, 'keptCount 停在 36');
});

test('桥 被抽帧并进前后两保留帧 合并计数对账', (): void => {
  const ops: Array<ArcOp> = feed30to24()[0];
  // 30→24 抽 9 枚，前 8 枚各夹在两保留帧之间 ⇒ 各 1 次 Accumulate(fresh)、1 次 Fold（并前帧）、
  // 1 次 useAcc 的 Merge（并后帧）；最后一枚（源 44）后面没有保留帧，它的补弧由 EOS 的 Fold 收尾
  // ⇒ Fold 总数 9、useAcc 的 Merge 只有 8
  assertEq(9, countKind(ops, ArcOpKind.ACCUMULATE), 'Accumulate 9');
  for (const op of ops) {
    if (op.kind === ArcOpKind.ACCUMULATE) {
      assertTrue(op.fresh, '30→24 的抽帧互不相邻，全部 fresh');
    }
  }
  assertEq(9, countKind(ops, ArcOpKind.FOLD), 'Fold 9');
  let useAccMerges: number = 0;
  for (const op of ops) {
    if (op.kind === ArcOpKind.MERGE && op.useAcc) {
      useAccMerges++;
    }
  }
  assertEq(8, useAccMerges, 'useAcc Merge 8');
});

test('桥 连续抽帧时第二枚不fresh 不清残留也不漏累积', (): void => {
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(60, 60, 1, 24));
  const accs: Array<ArcOp> = [];
  for (let s: number = 0; s < 60; s++) {
    for (const op of flow.onSourceFrame(s)) {
      if (op.kind === ArcOpKind.ACCUMULATE) {
        accs.push(op);
      }
    }
  }
  assertTrue(accs.length > 0, '60→24 必有抽帧');
  assertTrue(accs[0].fresh, '首枚抽帧 fresh');
  let foundConsecutive: boolean = false;
  for (let i: number = 1; i < accs.length; i++) {
    if (accs[i - 1].fresh && !accs[i].fresh) {
      foundConsecutive = true;
    }
  }
  assertTrue(foundConsecutive, '60→24 必有连续抽帧的 fresh→非fresh 序列');
});

test('桥 EOS尾巴上的被抽帧并进最后一枚保留帧', (): void => {
  // 30→24/N=45：最后一枚源（44）恰是抽帧位次 ⇒ EOS 指令 = [Fold, Emit(最后一枚的PTS)]
  // （35 = 已发出的前 35 枚，待发的那枚正是输出第 35 位）
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(45, 30, 1, 24));
  for (let s: number = 0; s < 45; s++) {
    flow.onSourceFrame(s);
  }
  const tail: Array<ArcOp> = flow.onSourceEos();
  assertEq(2, tail.length, '尾巴两条指令');
  assertEq(ArcOpKind.FOLD, tail[0].kind, '先 Fold');
  assertEq(ArcOpKind.EMIT, tail[1].kind, '再 Emit');
  assertEq(Math.floor(35 * 1000000 / 24), tail[1].ptsUs, '末 PTS = 35/24 秒');
});

test('桥 首保留帧之前的被抽帧直接弃 空流不炸', (): void => {
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(45, 30, 1, 24));
  // -1 是防御位（计划里负下标不保留），此时还没有滞留帧 ⇒ 空指令
  assertEq(0, flow.onSourceFrame(-1).length, '首保留前空指令');
  // 空流 EOS：没有滞留帧就没有输出
  const empty: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(0, 30, 1, 24));
  assertEq(0, empty.onSourceEos().length, '空流 EOS 空指令');
  // 在线分类路：未保留且无 pending 也为空
  const online: ArcRepairFlow = ArcRepairFlow.ofDstFps(24);
  assertEq(0, online.onClassification(false).length, '在线路首帧即抽：空指令');
});

test('整段枚举 45帧逐帧对账', (): void => {
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(ArcRepairPlan.of(45, 30, 1, 24));
  let emitted: number = 0;
  let pending: boolean = false;
  let hasAcc: boolean = false;
  for (let s: number = 0; s < 45; s++) {
    for (const op of flow.onSourceFrame(s)) {
      if (op.kind === ArcOpKind.ACCUMULATE) {
        assertTrue(pending, 'Accumulate 必在滞留之后');
        assertEq(!hasAcc, op.fresh, 'fresh 口径：acc 空时才 fresh');
        hasAcc = true;
      } else if (op.kind === ArcOpKind.FOLD) {
        assertTrue(hasAcc, 'Fold 必伴随未消费的 acc');
      } else if (op.kind === ArcOpKind.HOLD) {
        assertFalse(pending, 'Hold 只发生在首滞留');
        pending = true;
      } else if (op.kind === ArcOpKind.MERGE) {
        assertTrue(pending, 'Merge 只发生在已有滞留');
        assertEq(hasAcc, op.useAcc, 'useAcc = 本拍仍有 acc 语义');
        hasAcc = false;
      } else {
        assertTrue(pending, 'Emit 必须有滞留帧');
        assertEq(Math.floor(emitted * 1000000 / 24), op.ptsUs, 'PTS 与发出序号绑定');
        emitted++;
      }
    }
  }
  const tail: Array<ArcOp> = flow.onSourceEos();
  assertTrue(tail.length > 0, '尾巴有指令');
  for (const op of tail) {
    if (op.kind === ArcOpKind.EMIT) {
      emitted++;
    }
  }
  assertEq(ArcRepairPlan.dstFrameCount(45, 30, 1, 24), emitted, '枚举发出的帧数=计划帧数');
});

test('桥 NTSC源整流 发出721帧末PTS对齐目标步长', (): void => {
  const plan: ArcRepairPlan = ArcRepairPlan.of(900, 30000, 1001, 24);
  const flow: ArcRepairFlow = ArcRepairFlow.ofPlan(plan);
  let emitted: number = 0;
  let lastPts: number = -1;
  for (let s: number = 0; s < 900; s++) {
    for (const op of flow.onSourceFrame(s)) {
      if (op.kind === ArcOpKind.EMIT) {
        emitted++;
        lastPts = op.ptsUs;
      }
    }
  }
  for (const op of flow.onSourceEos()) {
    if (op.kind === ArcOpKind.EMIT) {
      emitted++;
      lastPts = op.ptsUs;
    }
  }
  assertEq(721, emitted, '900 源帧发 721 帧');
  assertEq(Math.floor(720 * 1000000 / 24), lastPts, '末帧 PTS = 720/24 秒');
});
