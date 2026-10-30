#!/usr/bin/env python3
"""docs/learning/ 质量检查。

检查项：
  1. 站内链接：docs/learning 内的 .md 相对链接必须指向本目录内文件；
     指向计划清单内尚未生成的文件记 pending（软告警），指向清单外缺失文件记 FAIL。
  2. 仓库路径引用：反引号内形似仓库路径的 token 必须能在 `git ls-files` 中找到。
  3. 结构：知识文档（10-82）须含「自测」节；行数下限；无占位符。
  4. 数字审计：列出含性能数字的行供人工复核（软告警）。

用法：python docs/learning/check.py [--files 相对名1 相对名2 ...]
退出码：有 FAIL 记 1，否则 0。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEARN = ROOT / "docs" / "learning"

PLANNED = {
    "README.md", "00-roadmap.md", "01-job-market.md", "02-capability-matrix.md",
    "10-java-basics.md",
    "java/10-01-types-and-references.md", "java/10-02-oop.md",
    "java/10-03-equality-and-strings.md", "java/10-04-wrappers-and-boxing.md",
    "java/10-05-generics.md", "java/10-06-exceptions.md",
    "java/10-07-annotations-reflection-proxy.md", "java/10-08-lambda-stream-optional.md",
    "java/10-09-enum-datetime-bigdecimal.md", "java/10-10-java17.md",
    "11-java-collections.md", "12-jvm.md", "13-concurrency.md",
    "14-algorithms.md", "20-network.md", "21-os.md", "22-tools.md",
    "30-spring-core.md", "31-spring-boot-mvc.md", "32-spring-security.md",
    "40-mysql.md", "41-mybatis-plus.md", "50-redis.md", "51-rabbitmq-outbox.md",
    "60-testing-ci.md", "61-docker-deploy.md", "62-design-patterns.md",
    "70-distributed.md", "80-linkforge-architecture.md", "81-linkforge-flows.md",
    "82-linkforge-decisions.md", "90-interview-knowledge.md", "91-interview-project.md",
    "92-interview-coding.md", "93-glossary-review.md", "94-scenario-drills.md",
    "95-final-selftest.md",
}

PATH_EXT = {".java", ".xml", ".yml", ".yaml", ".sql", ".lua", ".sh", ".html",
            ".json", ".properties", ".example", ".md", ".py", ".gitignore"}
STUB_PAT = re.compile(r"TODO(?!U)|待补充|FIXME|<placeholder>|待填写|待完成")
NUM_PAT = re.compile(r"(QPS|吞吐|P9[59]|TPS).{0,40}\d|\d{2,}\s*(QPS|TPS)")

fails, warns, pendings = [], [], []


def repo_files():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace").stdout
    entries = set(out.splitlines())
    basenames = {e.rsplit("/", 1)[-1] for e in entries}
    return entries, basenames


def check_doc(name, entries, basenames):
    path = LEARN / name
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    num = int(name[:2]) if name[:2].isdigit() else 0
    is_meta = num < 10 or num >= 90
    min_lines = 50 if name == "README.md" else (85 if is_meta else 110)
    if len(lines) < min_lines:
        fails.append(f"{name}: 行数 {len(lines)} < {min_lines}")
    for i, ln in enumerate(lines, 1):
        if STUB_PAT.search(ln):
            fails.append(f"{name}:{i}: 占位/未完成标记: {ln.strip()[:60]}")
    if not is_meta and "自测" not in text:
        fails.append(f"{name}: 缺自测节")
    h2 = sum(1 for ln in lines if ln.startswith("## "))
    if h2 < 3:
        warns.append(f"{name}: 仅 {h2} 个二级标题")

    for target in re.findall(r"\]\(([^)#\s]+\.md)(?:#[^)]*)?\)", text):
        resolved = (path.parent / target).resolve()
        rel = resolved.relative_to(ROOT).as_posix() if ROOT in resolved.parents else None
        if rel is None or not rel.startswith("docs/learning/"):
            if not (resolved.exists() and resolved.is_file()):
                fails.append(f"{name}: 链接目标不存在或越出 docs/learning: {target}")
        elif not resolved.exists():
            learn_rel = resolved.relative_to(LEARN).as_posix()
            if resolved.name in PLANNED or learn_rel in PLANNED:
                pendings.append(f"{name}: 链接指向待生成文件 {learn_rel}")
            else:
                fails.append(f"{name}: 链接目标不存在 {target}")

    for tok in set(re.findall(r"`([^`\n]+)`", text)):
        if " " in tok or "\t" in tok or "{" in tok or tok.startswith(("/", "http", "GET ", "POST ", "curl")):
            continue
        suffix = Path(tok).suffix.lower()
        if "/" in tok and suffix in PATH_EXT:
            if tok not in entries:
                fails.append(f"{name}: 仓库路径引用不存在: {tok}")
        elif "/" not in tok and suffix in {".yml", ".yaml", ".sql", ".lua", ".sh", ".example", ".gitignore"}:
            if tok not in basenames:
                fails.append(f"{name}: 文件名引用不存在: {tok}")

    for i, ln in enumerate(lines, 1):
        if NUM_PAT.search(ln):
            warns.append(f"{name}:{i}: 性能数字待复核: {ln.strip()[:70]}")


def main():
    args = sys.argv[1:]
    targets = sorted(PLANNED) if "--files" not in args else args[args.index("--files") + 1:]
    entries, basenames = repo_files()
    for name in targets:
        check_doc(name, entries, basenames)
    for name in targets:
        if name != "check.py" and name not in PLANNED:
            fails.append(f"清单外文件混入: {name}")

    print(f"== docs/learning 检查：{len(targets)} 个文件 ==")
    for p in pendings:
        print(f"PENDING  {p}")
    for w in warns:
        print(f"WARN     {w}")
    for f in fails:
        print(f"FAIL     {f}")
    print(f"== 结果：{len(fails)} FAIL / {len(warns)} WARN / {len(pendings)} PENDING ==")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
