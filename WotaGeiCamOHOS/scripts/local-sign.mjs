#!/usr/bin/env node
/**
 * HAP 本地签名探针（T23 方案 C）：用 DevEco 自带 hap-sign-tool.jar 走完整本地签名链——
 *   generate-keypair(ECC P-256 ×4) → generate-ca(root/sub) → generate-app-cert
 *   → generate-profile-cert → sign-profile(debug, bundleName com.wotagei.cam)
 *   → sign-app → verify-app（自动回验：还原证书链与 profile）
 *
 * 密钥/证书落 .local-signing/（已进 .gitignore；材料**只在缺失时生成**，跨次运行保持稳定，
 * 便于同证书反复重签/覆盖安装）；产物 entry-default-signed.hap 落构建输出目录（unsigned 旁）。
 *
 * 平台口径（重要登记）：HarmonyOS NEXT 工具链的 app/profile 证书强制 ECDSA 签名算法
 * （SHA256withRSA 会报 11011002），故整链用 ECC NIST-P-256 + SHA256withECDSA；
 * 新式签名写进 zip EOCD 签名块（HarmonyAppSigingBlock），**不产生旧式 META-INF 目录条目**，
 * 「unzip 可见签名块与 profile」以 verify-app 能完整还原证书链+profile 为准（本脚本自动执行）。
 *
 * 本签名仅证明包结构可签名/可验证（交付口径）；自签证书未经华为云颁发，
 * 真机安装请用 DevEco Studio 自动签名（AGC 签名材料），见 README「交付」节。
 *
 * 用法：node scripts/local-sign.mjs   （仓库根 WotaGeiCamOHOS/ 下执行）
 * 环境变量：DEVECO_SDK_HOME 或 HAP_SIGN_TOOL_JAR（jar 全路径）定位工具；
 *           UNSIGNED_HAP 覆盖待签包路径；JAVA_BIN 覆盖 java（默认 PATH）；
 *           LOCAL_SIGN_PASS 覆盖本地探针口令（默认值仅用于自签测试材料）。
 */
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, '.local-signing');
const BUNDLE = 'com.wotagei.cam';
// 仅本地结构探针口令；可用 LOCAL_SIGN_PASS 覆盖。绝不用于生产/发布签名。
const STORE_PASS = process.env.LOCAL_SIGN_PASS || 'wota-local-123456';

const ROOT_SUBJ = 'C=CN,O=WotaGeiCam Local,OU=Local Sign,CN=WotaGeiCam Local Root CA';
const SUB_SUBJ = 'C=CN,O=WotaGeiCam Local,OU=Local Sign,CN=WotaGeiCam Local Sub CA';
const APP_SUBJ = 'C=CN,O=WotaGeiCam Local,OU=Local Sign,CN=WotaGeiCam Debug';
const PROFILE_SUBJ = 'C=CN,O=WotaGeiCam Local,OU=Local Sign,CN=WotaGeiCam Provision Profile Debug';

const P12 = path.join(OUT_DIR, 'wota-local.p12');
const ROOT_CA = path.join(OUT_DIR, 'root-ca.cer');
const SUB_CA = path.join(OUT_DIR, 'sub-ca.cer');
const APP_CERT = path.join(OUT_DIR, 'app-cert.cer');
const PROFILE_CERT = path.join(OUT_DIR, 'profile-cert.cer');
const PROFILE_JSON = path.join(OUT_DIR, 'wota-debug-profile.json');
const PROFILE_P7B = path.join(OUT_DIR, 'wota-debug.p7b');
const VERIFY_CERT = path.join(OUT_DIR, 'verified-cert-chain.cer');
const VERIFY_PROFILE = path.join(OUT_DIR, 'verified-profile.p7b');

function findJar() {
  const fromEnv = process.env.HAP_SIGN_TOOL_JAR;
  if (fromEnv && fs.existsSync(fromEnv)) {
    return fromEnv;
  }
  const home = process.env.DEVECO_SDK_HOME || 'D:/Application/HUAWEI/DevEco/DevEco Studio/sdk';
  const guess = path.join(home, 'default', 'openharmony', 'toolchains', 'lib', 'hap-sign-tool.jar');
  if (fs.existsSync(guess)) {
    return guess;
  }
  throw new Error(`hap-sign-tool.jar not found (set HAP_SIGN_TOOL_JAR or DEVECO_SDK_HOME): ${guess}`);
}

function findUnsignedHap() {
  const fromEnv = process.env.UNSIGNED_HAP;
  if (fromEnv && fs.existsSync(fromEnv)) {
    return fromEnv;
  }
  const guess = path.join(ROOT, 'entry', 'build', 'default', 'outputs', 'default',
    'entry-default-unsigned.hap');
  if (fs.existsSync(guess)) {
    return guess;
  }
  throw new Error(`unsigned hap not found (build first or set UNSIGNED_HAP): ${guess}`);
}

/** hap-sign-tool 单步执行：输出行里没有 success 即失败上抛 */
function signTool(jar, label, args) {
  const javaBin = process.env.JAVA_BIN || 'java';
  const r = spawnSync(javaBin, ['-jar', jar, ...args], { encoding: 'utf8' });
  const text = (r.stdout || '') + (r.stderr || '');
  if (r.status !== 0 || !/success/i.test(text)) {
    throw new Error(`${label} failed (exit=${r.status}):\n${text.trim().slice(0, 2000)}`);
  }
  process.stdout.write(`[local-sign] ${label} ok\n`);
}

function ensureMaterial(jar) {
  const keyPairs = ['wota-root', 'wota-sub', 'wota-app', 'wota-profile'];
  if (fs.existsSync(P12) && fs.existsSync(ROOT_CA) && fs.existsSync(SUB_CA) &&
    fs.existsSync(APP_CERT) && fs.existsSync(PROFILE_CERT)) {
    process.stdout.write('[local-sign] material exists, reuse (delete .local-signing/ to regenerate)\n');
    return;
  }
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });
  for (const alias of keyPairs) {
    signTool(jar, `generate-keypair ${alias}`, [
      'generate-keypair', '-keyAlias', alias, '-keyPwd', STORE_PASS,
      '-keyAlg', 'ECC', '-keySize', 'NIST-P-256',
      '-keystoreFile', P12, '-keystorePwd', STORE_PASS
    ]);
  }
  signTool(jar, 'generate-ca root', [
    'generate-ca', '-keyAlias', 'wota-root', '-keyPwd', STORE_PASS,
    '-keyAlg', 'ECC', '-keySize', 'NIST-P-256',
    '-subject', ROOT_SUBJ, '-validity', '3650', '-signAlg', 'SHA256withECDSA',
    '-keystoreFile', P12, '-keystorePwd', STORE_PASS, '-outFile', ROOT_CA
  ]);
  signTool(jar, 'generate-ca sub', [
    'generate-ca', '-keyAlias', 'wota-sub', '-keyPwd', STORE_PASS,
    '-keyAlg', 'ECC', '-keySize', 'NIST-P-256',
    '-issuer', ROOT_SUBJ, '-issuerKeyAlias', 'wota-root', '-issuerKeyPwd', STORE_PASS,
    '-subject', SUB_SUBJ, '-validity', '3650', '-signAlg', 'SHA256withECDSA',
    '-keystoreFile', P12, '-keystorePwd', STORE_PASS, '-outFile', SUB_CA
  ]);
  signTool(jar, 'generate-app-cert', [
    'generate-app-cert', '-keyAlias', 'wota-app', '-keyPwd', STORE_PASS,
    '-signAlg', 'SHA256withECDSA', '-issuer', SUB_SUBJ,
    '-issuerKeyAlias', 'wota-sub', '-issuerKeyPwd', STORE_PASS,
    '-subject', APP_SUBJ, '-validity', '3650', '-outForm', 'certChain',
    '-rootCaCertFile', ROOT_CA, '-subCaCertFile', SUB_CA,
    '-keystoreFile', P12, '-keystorePwd', STORE_PASS, '-outFile', APP_CERT
  ]);
  signTool(jar, 'generate-profile-cert', [
    'generate-profile-cert', '-keyAlias', 'wota-profile', '-keyPwd', STORE_PASS,
    '-signAlg', 'SHA256withECDSA', '-issuer', SUB_SUBJ,
    '-issuerKeyAlias', 'wota-sub', '-issuerKeyPwd', STORE_PASS,
    '-subject', PROFILE_SUBJ, '-validity', '3650', '-outForm', 'certChain',
    '-rootCaCertFile', ROOT_CA, '-subCaCertFile', SUB_CA,
    '-keystoreFile', P12, '-keystorePwd', STORE_PASS, '-outFile', PROFILE_CERT
  ]);
}

/** 签名材料仅本地探针使用：POSIX 下收紧 p12 权限（Windows ACL 不映射 0600，靠 .gitignore） */
function restrictMaterialPerms() {
  if (process.platform === 'win32') {
    return;
  }
  try {
    fs.chmodSync(P12, 0o600);
    process.stdout.write('[local-sign] chmod 600 wota-local.p12\n');
  } catch (e) {
    process.stdout.write(`[local-sign] WARN: chmod p12 failed: ${e && e.message ? e.message : String(e)}\n`);
  }
}

/** profile JSON：SDK 自带 debug 模板改 bundleName + 嵌入 app 证书叶 + 刷新有效期 */
function ensureProfileJson(jar) {
  if (fs.existsSync(PROFILE_JSON)) {
    return;
  }
  const jarDir = path.dirname(jar);
  const template = path.join(jarDir, 'UnsgnedDebugProfileTemplate.json');
  const tpl = JSON.parse(fs.readFileSync(template, 'utf8'));
  const appCert = fs.readFileSync(APP_CERT, 'utf8');
  const leaf = `${appCert.split('-----END CERTIFICATE-----')[0]}-----END CERTIFICATE-----\n`;
  tpl['bundle-info']['bundle-name'] = BUNDLE;
  tpl['bundle-info']['development-certificate'] = leaf;
  const now = Math.floor(Date.now() / 1000);
  tpl.validity['not-before'] = now - 86400;
  tpl.validity['not-after'] = now + 3650 * 86400;
  tpl['debug-info']['device-ids'] = []; // 不限设备（本地结构探针口径）
  fs.writeFileSync(PROFILE_JSON, JSON.stringify(tpl, null, 2));
  process.stdout.write('[local-sign] profile json written\n');
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

/** 失败清理：只留验证通过的成品——签名先写 .tmp，失败/回验不过即删，不碰已有 signed HAP */
let signedHapForCleanup = '';

function main() {
  const jar = findJar();
  const unsignedHap = findUnsignedHap();
  const signedHap = path.join(path.dirname(unsignedHap), 'entry-default-signed.hap');
  const signedTmp = `${signedHap}.tmp`;
  signedHapForCleanup = signedTmp;
  fs.rmSync(signedTmp, { force: true }); // 清上一次失败残留
  ensureMaterial(jar);
  restrictMaterialPerms();
  ensureProfileJson(jar);
  if (!fs.existsSync(PROFILE_P7B)) {
    signTool(jar, 'sign-profile', [
      'sign-profile', '-mode', 'localSign', '-keyAlias', 'wota-profile', '-keyPwd', STORE_PASS,
      '-profileCertFile', PROFILE_CERT, '-inFile', PROFILE_JSON,
      '-signAlg', 'SHA256withECDSA', '-keystoreFile', P12, '-keystorePwd', STORE_PASS,
      '-outFile', PROFILE_P7B
    ]);
  } else {
    process.stdout.write('[local-sign] signed profile exists, reuse\n');
  }
  signTool(jar, 'sign-app', [
    'sign-app', '-mode', 'localSign', '-keyAlias', 'wota-app', '-keyPwd', STORE_PASS,
    '-appCertFile', APP_CERT, '-profileFile', PROFILE_P7B,
    '-inFile', unsignedHap, '-signAlg', 'SHA256withECDSA',
    '-keystoreFile', P12, '-keystorePwd', STORE_PASS, '-outFile', signedTmp
  ]);
  // 自动回验：verify-app 从签名块还原证书链与 profile（新式 EOCD 签名块的权威取证）
  for (const f of [VERIFY_CERT, VERIFY_PROFILE]) {
    fs.rmSync(f, { force: true });
  }
  signTool(jar, 'verify-app', [
    'verify-app', '-inFile', signedTmp, '-outCertChain', VERIFY_CERT, '-outProfile', VERIFY_PROFILE
  ]);
  const certCount = (fs.readFileSync(VERIFY_CERT, 'utf8').match(/BEGIN CERTIFICATE/g) || []).length;
  if (certCount < 1) {
    throw new Error(`verify-app produced an empty cert chain: ${VERIFY_CERT}`);
  }
  const profileSize = fs.statSync(VERIFY_PROFILE).size;
  if (profileSize <= 0) {
    throw new Error(`verify-app produced an empty profile: ${VERIFY_PROFILE}`);
  }
  process.stdout.write(`[local-sign] verified: certChain=${certCount} certs, ` +
    `profile=${profileSize} bytes\n`);
  // 验证通过才原子替换成品；此前既有 signed HAP 保持不动
  fs.rmSync(signedHap, { force: true });
  fs.renameSync(signedTmp, signedHap);
  const size = fs.statSync(signedHap).size;
  process.stdout.write(`[local-sign] signed hap: ${signedHap}\n` +
    `  size=${size} bytes sha256=${sha256(signedHap)}\n` +
    `  unsigned: ${fs.statSync(unsignedHap).size} bytes sha256=${sha256(unsignedHap)}\n`);
}

try {
  main();
} catch (e) {
  if (signedHapForCleanup !== '' && fs.existsSync(signedHapForCleanup)) {
    try {
      fs.rmSync(signedHapForCleanup, { force: true });
      console.error(`[local-sign] removed unverified signed hap temp: ${signedHapForCleanup}`);
    } catch (cleanupErr) {
      console.error(`[local-sign] failed to remove signed hap: ` +
        `${cleanupErr && cleanupErr.message ? cleanupErr.message : String(cleanupErr)}`);
    }
  }
  console.error(`[local-sign] FAILED: ${e && e.message ? e.message : String(e)}`);
  process.exit(1);
}
