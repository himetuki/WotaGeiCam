#!/usr/bin/env node
/**
 * 纯逻辑测试架（自研轻量方案，不用 hypium/ohosTest）：
 * - 扫描 test/pure/*.test.ets（环境变量 PURE_TEST_DIR 可覆盖，供三态自检）；
 * - 正则收集 import 依赖闭包（从 entry/src/main/ets/ 起，相对路径递归展开）；
 * - typescript.transpileModule(CommonJS/ES2020) 转译到 .pure-test-tmp/ 镜像相对路径
 *   （typescript 复用 DevEco 自带 $DEVECO_HOME/tools/hvigor/hvigor/node_modules/typescript，
 *   环境变量 PURE_TEST_TS_PATH 可覆盖，指向 typescript 模块目录或其 package.json）；
 * - require 钩子把 @ohos.* / @kit.* 桩成惰性 Proxy（顶层 import 不炸，未触达的属性/调用
 *   全部惰性返回子桩——纯逻辑测试永不真正调用平台 API）；
 * - 注入迷你断言 API：test(name, fn) / assertEq / assertTrue / assertFalse / assertNull；
 * - 汇总 passed/failed，任何失败（含加载失败）退出码非 0；零测试退出 0。
 *
 * 用法：node scripts/pure-test.mjs   （仓库根 WotaGeiCamOHOS/ 下执行）
 */
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TEST_DIR = path.resolve(ROOT, process.env.PURE_TEST_DIR || path.join('test', 'pure'));
const TMP_DIR = path.join(ROOT, '.pure-test-tmp');

function loadTypescript() {
  const fromEnv = process.env.PURE_TEST_TS_PATH;
  const candidates = [];
  if (fromEnv) {
    candidates.push(fromEnv, path.join(fromEnv, 'typescript.js'), path.join(fromEnv, 'lib', 'typescript.js'));
  }
  const home = process.env.DEVECO_HOME || 'D:/Application/HUAWEI/DevEco/DevEco Studio';
  candidates.push(path.join(home, 'tools', 'hvigor', 'hvigor', 'node_modules', 'typescript'));
  for (const c of candidates) {
    for (const resolved of [c, path.join(c, 'typescript.js'), path.join(c, 'lib', 'typescript.js')]) {
      try {
        const ts = require(resolved);
        if (ts && typeof ts.transpileModule === 'function') {
          return ts;
        }
      } catch (e) {
        // 试下一个候选路径
      }
    }
  }
  console.error('pure-test: typescript not found (set PURE_TEST_TS_PATH or DEVECO_HOME)');
  process.exit(2);
}

/** import 语句的模块说明符提取（覆盖多行 import 与 import type；@ohos/@kit 运行期桩掉） */
const IMPORT_RE = /^[ \t]*import\b[^;]*?from\s*['"]([^'"]+)['"]/gm;

function listTestFiles(dir) {
  if (!fs.existsSync(dir)) {
    return [];
  }
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith('.test.ets'))
    .sort()
    .map((f) => path.join(dir, f));
}

function resolveRelative(spec, fromFile) {
  const base = path.resolve(path.dirname(fromFile), spec);
  const candidates = [base, base + '.ets', path.join(base, 'index.ets')];
  for (const c of candidates) {
    if (fs.existsSync(c) && fs.statSync(c).isFile()) {
      return c;
    }
  }
  return undefined;
}

/** 依赖闭包：test 文件 + 递归相对 import 的 .ets（只收 ROOT 下的文件） */
function collectClosure(entryFiles) {
  const closure = new Set();
  const queue = [...entryFiles];
  while (queue.length > 0) {
    const file = queue.pop();
    if (closure.has(file)) {
      continue;
    }
    closure.add(file);
    const src = fs.readFileSync(file, 'utf8');
    let m;
    IMPORT_RE.lastIndex = 0;
    while ((m = IMPORT_RE.exec(src)) !== null) {
      const spec = m[1];
      if (!spec.startsWith('.')) {
        continue; // @ohos.*/@kit.*：运行期 stub；其余裸包名纯逻辑闭包不应出现
      }
      const resolved = resolveRelative(spec, file);
      if (resolved !== undefined && resolved.startsWith(ROOT + path.sep) && !closure.has(resolved)) {
        queue.push(resolved);
      }
    }
  }
  return closure;
}

function transpileToTmp(ts, files) {
  fs.rmSync(TMP_DIR, { recursive: true, force: true });
  fs.mkdirSync(TMP_DIR, { recursive: true });
  for (const file of files) {
    const src = fs.readFileSync(file, 'utf8');
    const out = ts.transpileModule(src, {
      fileName: file,
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2020,
        isolatedModules: true
      }
    });
    const rel = path.relative(ROOT, file).replace(/\.ets$/, '.js');
    const dest = path.join(TMP_DIR, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, out.outputText, 'utf8');
    const dts = path.join(path.dirname(dest), path.basename(dest, '.js') + '.d.ts');
    fs.writeFileSync(dts, 'export {};\n', 'utf8'); // 防同目录残留声明干扰（无副作用）
  }
}

/** @ohos.* / @kit.* 惰性桩：可调用 Proxy，任意属性返回子桩，任意调用返回子桩 */
function kitStub(id) {
  const fn = function stubCall() {
    return kitStub(`${id}()`);
  };
  return new Proxy(fn, {
    get(_t, prop) {
      if (prop === '__esModule') {
        return true;
      }
      if (prop === 'then' || prop === 'catch' || prop === 'finally') {
        return undefined; // 不伪装成 thenable，避免 await 桩走进微任务死路
      }
      if (typeof prop === 'symbol') {
        if (prop === Symbol.toPrimitive) {
          return () => 0;
        }
        return undefined;
      }
      return kitStub(`${id}.${String(prop)}`);
    },
    apply() {
      return kitStub(`${id}()`);
    }
  });
}

function installRequireHook() {
  const Module = require('module');
  const origLoad = Module._load;
  Module._load = function patchedLoad(request, parent, isMain) {
    if (typeof request === 'string' && (request.startsWith('@ohos.') || request.startsWith('@kit.'))) {
      return kitStub(request);
    }
    return origLoad.apply(this, arguments);
  };
}

function installAssertApi(tests) {
  const fmt = (v) => {
    try {
      return typeof v === 'string' ? `'${v}'` : String(v);
    } catch (e) {
      return '<unstringifiable>';
    }
  };
  globalThis.test = (name, fn) => {
    tests.push({ name: String(name), fn });
  };
  globalThis.assertEq = (expected, actual, msg = '') => {
    if (!Object.is(expected, actual)) {
      throw new Error(`${msg === '' ? '' : msg + ': '}expected ${fmt(expected)} but got ${fmt(actual)}`);
    }
  };
  globalThis.assertTrue = (v, msg = '') => {
    if (v !== true) {
      throw new Error(`${msg === '' ? '' : msg + ': '}expected true but got ${fmt(v)}`);
    }
  };
  globalThis.assertFalse = (v, msg = '') => {
    if (v !== false) {
      throw new Error(`${msg === '' ? '' : msg + ': '}expected false but got ${fmt(v)}`);
    }
  };
  globalThis.assertNull = (v, msg = '') => {
    if (v !== null && v !== undefined) {
      throw new Error(`${msg === '' ? '' : msg + ': '}expected null/undefined but got ${fmt(v)}`);
    }
  };
}

async function run() {
  const ts = loadTypescript();
  const testFiles = listTestFiles(TEST_DIR);
  const tests = [];
  installAssertApi(tests);
  installRequireHook();

  if (testFiles.length === 0) {
    console.log('pure-test: no *.test.ets found, nothing to run');
    return 0;
  }

  const closure = collectClosure(testFiles);
  transpileToTmp(ts, closure);

  let loadFailures = 0;
  const loadedFiles = [];
  for (const f of testFiles) {
    const rel = path.relative(ROOT, f).replace(/\\/g, '/');
    const js = path.join(TMP_DIR, path.relative(ROOT, f).replace(/\.ets$/, '.js'));
    try {
      require(js);
      loadedFiles.push(rel);
    } catch (e) {
      loadFailures++;
      console.log(`[FAIL] ${rel} :: <load> ${e && e.message ? e.message : String(e)}`);
    }
  }

  let passed = 0;
  let failed = loadFailures;
  for (const t of tests) {
    try {
      await t.fn();
      passed++;
      console.log(`[PASS] ${t.name}`);
    } catch (e) {
      failed++;
      console.log(`[FAIL] ${t.name} :: ${e && e.message ? e.message : String(e)}`);
    }
  }
  console.log(`---- pure-test: files=${loadedFiles.length} tests=${tests.length} passed=${passed} failed=${failed}`);
  return failed > 0 ? 1 : 0;
}

run().then((code) => {
  process.exit(code);
}).catch((e) => {
  console.error(`pure-test: harness error ${e && e.stack ? e.stack : String(e)}`);
  process.exit(2);
});
