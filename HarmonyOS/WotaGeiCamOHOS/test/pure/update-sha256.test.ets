/**
 * SHA-256 端到端校验链的纯函数单测——上游 UpdateSha256Test.kt 的摘要/判据部分。
 *
 * 上游用 java.security.MessageDigest（JVM 面）；鸿蒙侧要能在 pure-test 里跑，改为自带纯实现
 * （entry/src/main/ets/update/Sha256.ets），因此这里用 **NIST FIPS 180-4 标准向量**钉住：
 * 空串 / "abc" / 448-bit / 896-bit 四条官方向量 + 分块流式等价性 + 64KiB 边界。
 * 上游的「文件」用例改为内存字节（平台文件 IO 不属纯逻辑）。
 */
import { Sha256 } from '../../entry/src/main/ets/update/Sha256';

function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

test('sha256_标准向量_空串与abc', () => {
  assertEq('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    Sha256.hexOf(new Uint8Array(0)));
  assertEq('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    Sha256.hexOf(utf8('abc')));
});

test('sha256_标准向量_448与896位', () => {
  assertEq('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    Sha256.hexOf(utf8('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')));
  assertEq('cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1',
    Sha256.hexOf(utf8('abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn' +
      'hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu')));
});

test('sha256_分块流式与一次性等价', () => {
  const data: Uint8Array = utf8('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq');
  const streaming: Sha256 = new Sha256();
  // 1 字节一块：覆盖 tail 缓冲的每一次拼接与压缩边界
  for (let i = 0; i < data.length; i++) {
    streaming.update(data.subarray(i, i + 1));
  }
  assertEq(Sha256.hexOf(data), streaming.digestHex());
});

test('sha256_64KiB边界与不同尾部产物不同', () => {
  const big: Uint8Array = new Uint8Array(64 * 1024 + 1);
  big.fill(0x78); // 'x'
  const other: Uint8Array = new Uint8Array(64 * 1024 + 1);
  other.fill(0x78);
  other[other.length - 1] = 0x7a; // 'z'
  const a: string = Sha256.hexOf(big);
  const b: string = Sha256.hexOf(other);
  assertEq(64, a.length);
  assertTrue(a !== b, '64KiB+1 的末字节不同，摘要必须不同');
});

test('sha256_digest后不可再update', () => {
  const h: Sha256 = new Sha256();
  h.update(utf8('abc'));
  h.digestHex();
  let threw: boolean = false;
  try {
    h.update(utf8('more'));
  } catch (e) {
    threw = true;
  }
  assertTrue(threw);
});
