#!/usr/bin/env python3
"""WotaGeiCam iOS 源清单 / 映射结构覆盖核验（静态检查，非行为测试）。

只做「源路径存在、必填列、稳定 ID、不重复、状态词表、明确排除 AI」的**静态**核验；
不冒充 Swift/Metal 编译、不跑模拟器、不截图、不做行为测试。

用法（建议以 -I 运行，避免加载工作区外的模块）：
    python -I iOS/scripts/verify_source_inventory.py

退出码：0 = 全部通过；1 = 任一检查失败。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IOS_ROOT = REPO_ROOT / "iOS"
MAPPINGS = IOS_ROOT / "docs" / "mappings"

CHECKS = 0
FAILURES: list[str] = []


def check(condition: bool, message: str) -> bool:
    global CHECKS
    CHECKS += 1
    if not condition:
        FAILURES.append(message)
    return condition


# ---------------------------------------------------------------- 配置

# 交付物清单（本任务允许写入的文件）
REQUIRED_FILES = [
    "docs/mappings/README.md",
    "docs/mappings/features.md",
    "docs/mappings/settings.md",
    "docs/mappings/storage.md",
    "docs/mappings/pages.md",
    "docs/mappings/platform-differences.md",
    "docs/mappings/manual-verification.md",
    "UPSTREAM_SYNC.md",
    "scripts/verify_source_inventory.py",
]

# 每个映射文件：ID 前缀 / 必填列 / 允许的状态前缀 / 最少行数
# 状态校验用「前缀匹配」：`待实现（…）` 等带注记的写法视为合法，但不许含「完成 / 通过」。
FILE_SPEC: dict[str, dict] = {
    "features.md": {
        "id_prefix": "F-",
        "required_cols": ["ID", "Android 源", "iOS 目标", "转换说明", "验证项", "状态"],
        "status_prefixes": ["待实现", "已实现（A2-S 骨架）", "排除（AI，不移植）"],
        "min_rows": 25,
    },
    "settings.md": {
        "id_prefix": "S-",
        "required_cols": ["ID", "Android 源", "状态"],
        "status_prefixes": ["待实现", "已实现（A2-S 骨架）", "排除（AI，不移植）"],
        "min_rows": 40,
    },
    "storage.md": {
        "id_prefix": "ST-",
        "required_cols": ["ID", "Android 源", "状态"],
        "status_prefixes": ["待实现", "已实现（A2-S 骨架）", "排除（AI，不移植）"],
        "min_rows": 25,
    },
    "pages.md": {
        "id_prefix": "P-",
        "required_cols": ["ID", "Android 源", "iOS 目标", "转换说明", "验证项", "状态"],
        "status_prefixes": ["待实现", "已实现（A2-S 骨架）", "排除（AI，不移植）"],
        "min_rows": 30,
    },
    "platform-differences.md": {
        "id_prefix": "PD-",
        "required_cols": ["ID", "Android 证据", "iOS 能力边界", "状态"],
        "status_prefixes": ["待实现"],
        "min_rows": 8,
    },
    "manual-verification.md": {
        "id_prefix": "MV-",
        "required_cols": ["ID", "验证项", "状态"],
        "status_prefixes": ["未执行"],
        "min_rows": 20,
    },
}

# 源路径 token：Android/....(kt|kts|xml|plist):NNN
SRC_TOKEN = re.compile(r"(Android/[A-Za-z0-9_./\-]+\.(?:kt|kts|xml|plist)):(\d+)")
# ID 形状：前缀-数字(.数字)?
FORBIDDEN_STATUS_WORDS = ("完成", "通过")


# ---------------------------------------------------------------- 工具

def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def split_cells(line: str) -> list[str]:
    """把一行 markdown 表格拆成单元格（去掉首尾空 |）。"""
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def is_separator_row(cells: list[str]) -> bool:
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", c) for c in cells)


def parse_tables(text: str) -> list[tuple[list[str], list[list[str]]]]:
    """返回 [(header_cells, [data_row_cells, ...]), ...]；仅含真正的 markdown 表格。"""
    tables: list[tuple[list[str], list[list[str]]]] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("|") and i + 1 < len(lines) and is_separator_row(split_cells(lines[i + 1])):
            header = split_cells(line)
            rows: list[list[str]] = []
            j = i + 2
            while j < len(lines) and lines[j].strip().startswith("|"):
                rows.append(split_cells(lines[j]))
                j += 1
            tables.append((header, rows))
            i = j
        else:
            i += 1
    return tables


def line_count(path: Path) -> int:
    return len(read_text(path).splitlines())


# ---------------------------------------------------------------- 检查

def check_required_files() -> None:
    for rel in REQUIRED_FILES:
        check((IOS_ROOT / rel).is_file(), f"缺少交付文件：iOS/{rel}")


def check_mapping_file(name: str, spec: dict, global_ids: dict[str, str]) -> None:
    path = MAPPINGS / name
    if not path.is_file():
        return
    text = read_text(path)

    check("已完成" not in text, f"{name}: 出现被禁用的「已完成」字样（未实现项必须标『待实现』）")

    id_re = re.compile(rf"^{re.escape(spec['id_prefix'])}\d+(?:\.\d+)?$")
    rows_total = 0
    mapping_tables = 0
    for header, rows in parse_tables(text):
        if "ID" not in header:
            continue  # 非映射表（如说明性表格）跳过
        mapping_tables += 1
        for col in spec["required_cols"]:
            check(col in header, f"{name}: 映射表缺少必填列『{col}』（表头={header}）")

        # 列索引
        def col_index(name_: str) -> int | None:
            return header.index(name_) if name_ in header else None

        status_idx = col_index("状态")
        src_idx = col_index("Android 源")
        if src_idx is None:
            src_idx = col_index("Android 证据")
        ios_idx = next((k for k, h in enumerate(header) if "iOS" in h), None)

        for row in rows:
            if len(row) < 2:
                continue
            rid = row[0]
            if not id_re.match(rid):
                check(False, f"{name}: ID『{rid}』不符合稳定前缀 {spec['id_prefix']}<数字>")
                continue
            rows_total += 1

            # 全局唯一
            if rid in global_ids:
                check(False, f"{name}: ID『{rid}』重复（已在 {global_ids[rid]} 出现）")
            else:
                global_ids[rid] = name

            # 状态
            if status_idx is not None and status_idx < len(row):
                status = row[status_idx]
                ok = any(status.startswith(p) for p in spec["status_prefixes"])
                check(ok, f"{name}: {rid} 状态『{status}』不在允许词表 {spec['status_prefixes']}")
                for bad in FORBIDDEN_STATUS_WORDS:
                    check(bad not in status, f"{name}: {rid} 状态含禁用词『{bad}』：{status}")

            # 源路径 + 行号存在
            if src_idx is not None and src_idx < len(row):
                tokens = SRC_TOKEN.findall(row[src_idx])
                check(bool(tokens), f"{name}: {rid} 的『Android 源』列没有可核验的 路径:行 令牌")
                for rel, num in tokens:
                    fpath = REPO_ROOT / rel
                    if not check(fpath.is_file(), f"{name}: {rid} 源文件不存在：{rel}"):
                        continue
                    ln = int(num)
                    total = line_count(fpath)
                    check(ln <= total, f"{name}: {rid} 源行号 {ln} 超出 {rel} 行数 {total}")

            # iOS 目标非空
            if ios_idx is not None and ios_idx < len(row):
                check(bool(row[ios_idx]), f"{name}: {rid} 的 iOS 目标列为空")

    check(mapping_tables >= 1, f"{name}: 未找到含 ID 列的映射表")
    check(rows_total >= spec["min_rows"],
          f"{name}: 映射行数 {rows_total} 少于覆盖下限 {spec['min_rows']}")


def check_ai_exclusion() -> None:
    """AI 必须被明确排除：文档计数 + iOS 源码树无 AI 符号。"""
    for name in ("features.md", "settings.md"):
        path = MAPPINGS / name
        if not path.is_file():
            continue
        count = read_text(path).count("排除（AI，不移植）")
        check(count >= 6, f"{name}: 明确排除 AI 的行数过少（{count} < 6）")

    # 匹配标识符起始（含 snake_case / camelCase 分段），不要把 distance 等普通单词当作功能符号。
    swift_files = list((IOS_ROOT / "WotaGeiCam").rglob("*.swift")) + \
        list((IOS_ROOT / "Packages").rglob("*.swift"))
    check(len(swift_files) > 0, "iOS 源码树未找到任何 .swift 文件")
    ai_symbol = re.compile(r"(?<![a-zA-Z0-9])(?:[Ww]ota)?[Ss]tance|(?<=[a-z0-9])Stance|(?<![a-zA-Z0-9])(?:WOTA)?STANCE")
    hits: list[str] = []
    for f in swift_files:
        if ai_symbol.search(read_text(f)):
            hits.append(str(f.relative_to(REPO_ROOT)))
    check(not hits, f"iOS 源码树出现 AI 站位符号（应为零）：{hits}")


def check_upstream_sync() -> None:
    path = IOS_ROOT / "UPSTREAM_SYNC.md"
    if not path.is_file():
        return
    text = read_text(path)
    for token, desc in [
        ("7394187", "基线提交"),
        ("UPSTREAM_BASE", "基线文件"),
        ("origin/main", "上游权威"),
        ("人工移植", "人工移植流程"),
        ("merge", "禁止 merge 的说明"),
        ("分步", "分步本地提交"),
        ("不推送", "禁止推送纪律"),
    ]:
        check(token in text, f"UPSTREAM_SYNC.md: 缺少『{desc}』关键词『{token}』")


def check_shell_script_locale_safety() -> None:
    """v0.0.12-r14 CI 实证：`$var` 后紧跟多字节字符时，C locale 的 bash 会把
    该字节吸收进变量名，报 `unbound variable`（macOS runner 无 UTF-8 locale）。
    因此 iOS 脚本里所有「$var 紧跟非 ASCII」必须写成 `${var}`；此检查让
    structure-checks 在 ubuntu 上提前拦截，不再等 macOS job 失败。
    """
    pattern = re.compile(rb"\$[A-Za-z_][A-Za-z0-9_]*(?=[^\x00-\x7f])")
    for path in sorted((IOS_ROOT / "scripts").glob("*.sh")):
        data = path.read_bytes()
        hits = []
        for m in pattern.finditer(data):
            line = data.count(b"\n", 0, m.start()) + 1
            hits.append(f"{path.relative_to(REPO_ROOT)}:{line}")
        check(
            not hits,
            "iOS 脚本存在 `$var` 紧跟多字节字符（runner 的 C locale 会解析成未定义变量，"
            f"必须改写为 ${{var}}）：{hits}",
        )


def main() -> int:
    check_required_files()

    global_ids: dict[str, str] = {}
    for name, spec in FILE_SPEC.items():
        check_mapping_file(name, spec, global_ids)

    check_ai_exclusion()
    check_upstream_sync()
    check_shell_script_locale_safety()

    print(f"checks={CHECKS} failures={len(FAILURES)}")
    if FAILURES:
        print("FAIL")
        for msg in FAILURES:
            print(f"  - {msg}")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
