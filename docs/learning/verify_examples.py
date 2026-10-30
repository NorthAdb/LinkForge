#!/usr/bin/env python3
"""docs/learning Java 示例校验。

学习文档中每个「可运行示例」必须真实可运行且输出与文档一致。本脚本把约定固化为门禁：

  约定：```java 代码块后紧跟 ```text 块，text 块第一行必须是
    输出：        -> 编译并运行，stdout 必须与块内逐行一致
    输出（示意）  -> 编译并运行，只要求退出码 0，不比对输出（结果不确定的示例）
    编译错误：    -> 要求编译失败，且块内每行文字都出现在 javac 错误输出中
    运行异常：    -> 要求运行退出码非 0，且块内每行文字都出现在 stderr 中

  未配对的 java 块视为片段（FRAGMENT，不做校验），仅计数提醒。

用法：python docs/learning/verify_examples.py [--files 10-java-basics.md java/10-01-....md]
默认扫描 10-java-basics.md 与 java/ 目录全部文档。
编译参数 --release 17，与项目 pom.xml 的 java.version=17 对齐；JDK 需在 PATH。
退出码：有 FAIL 记 1，否则 0。
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEARN = ROOT / "docs" / "learning"

JAVA_FENCE = re.compile(r"^```java[ \t]*$", re.M)
FENCE_END = re.compile(r"^```[ \t]*$", re.M)
TEXT_FENCE = re.compile(r"^\n*```text[ \t]*\r?\n")
TYPE_DECL = re.compile(r"\b(?:public\s+)?(class|interface|enum|record)\s+(\w+)")
KINDS = ("编译错误", "运行异常", "输出")


def extract_pairs(text):
    """返回 (pairs, fragments)。pair = (java 代码, text 块内容, 行号)。"""
    pairs, fragments = [], []
    for m in JAVA_FENCE.finditer(text):
        start = m.end()
        end_m = FENCE_END.search(text, start)
        if not end_m:
            continue
        code = text[start:end_m.start()].strip("\n")
        line_no = text[:m.start()].count("\n") + 1
        rest = text[end_m.end():]
        t = TEXT_FENCE.match(rest)
        if not t:
            fragments.append(line_no)
            continue
        t_end_m = FENCE_END.search(rest, t.end())
        if not t_end_m:
            fragments.append(line_no)
            continue
        expect = rest[t.end():t_end_m.start()].strip("\n")
        pairs.append((code, expect, line_no))
    return pairs, fragments


def java_file_name(code):
    pub = re.search(r"\bpublic\s+(?:class|interface|enum|record)\s+(\w+)", code)
    if pub:
        return pub.group(1) + ".java"
    decl = TYPE_DECL.search(code)
    if decl:
        return decl.group(2) + ".java"
    return None


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def run_pair(code, expect, tmp, results, label):
    name = java_file_name(code)
    if not name:
        results.append((label, "FAIL", "代码块中找不到类/接口/枚举/record 声明，无法确定文件名"))
        return
    src = tmp / name
    src.write_text(code + "\n", encoding="utf-8")
    cp = subprocess.run(
        ["javac", "--release", "17", "-encoding", "UTF-8",
         "-J-Duser.language=en", "-J-Duser.country=US", "-d", str(tmp), str(src)],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    kind = ""
    lines = expect.splitlines()
    if lines and lines[0].strip().startswith(KINDS):
        kind = lines[0].strip()
        lines = lines[1:]
    body = [ln for ln in lines if ln.strip()]
    if kind.startswith("编译错误"):
        if cp.returncode == 0:
            results.append((label, "FAIL", "预期编译失败，但编译通过"))
            return
        err = norm(cp.stderr)
        missing = [ln for ln in body if norm(ln) not in err]
        if missing:
            results.append((label, "FAIL", "编译失败符合预期，但这些描述未出现在 javac 输出: " + " | ".join(missing)))
        else:
            results.append((label, "OK", ""))
        return
    if cp.returncode != 0:
        first = (cp.stderr.strip().splitlines() or [""])[0]
        results.append((label, "FAIL", "编译失败: " + first))
        return
    run = subprocess.run(
        ["java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
         "-Dfile.encoding=UTF-8", "-cp", str(tmp), name[:-5]],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if kind.startswith("运行异常"):
        if run.returncode == 0:
            results.append((label, "FAIL", "预期运行异常，但退出码为 0"))
            return
        err = norm(run.stderr)
        missing = [ln for ln in body if norm(ln) not in err]
        if missing:
            results.append((label, "FAIL", "异常符合预期，但这些描述未出现在 stderr: " + " | ".join(missing)))
        else:
            results.append((label, "OK", ""))
        return
    if run.returncode != 0:
        first = (run.stderr.strip().splitlines() or [""])[0]
        results.append((label, "FAIL", "运行退出码 %d: %s" % (run.returncode, first)))
        return
    if "示意" in kind or "顺序" in kind:
        results.append((label, "OK", "(运行成功，输出示意未比对)"))
        return
    want = "\n".join(body)
    got = run.stdout.rstrip()
    if norm(want) != norm(got):
        results.append((label, "FAIL", "输出不一致\n  文档: %s\n  实际: %s" % (norm(want)[:200], norm(got)[:200])))
    else:
        results.append((label, "OK", ""))


def check_doc(rel, tmp):
    path = LEARN / rel
    text = path.read_text(encoding="utf-8", errors="replace")
    pairs, fragments = extract_pairs(text)
    results = []
    for code, expect, line_no in pairs:
        run_pair(code, expect, tmp, results, "%s:%d" % (rel, line_no))
    return results, len(fragments)


def main():
    args = sys.argv[1:]
    if "--files" in args:
        targets = args[args.index("--files") + 1:]
    else:
        targets = sorted(p.relative_to(LEARN).as_posix() for p in LEARN.rglob("*.md"))
    if not shutil.which("javac"):
        print("javac 不在 PATH，无法校验")
        sys.exit(2)
    total_fail = 0
    total_ok = 0
    for rel in targets:
        with tempfile.TemporaryDirectory() as td:
            results, frag = check_doc(rel, Path(td))
        fails = [r for r in results if r[1] == "FAIL"]
        total_fail += len(fails)
        total_ok += sum(1 for r in results if r[1] == "OK")
        print("== %s: %d 个示例，%d 通过，%d 失败，%d 个片段未校验" %
              (rel, len(results), len(results) - len(fails), len(fails), frag))
        for label, status, msg in results:
            if status == "FAIL":
                print("  FAIL %s %s" % (label, msg))
    print("== 合计：%d 通过 / %d 失败 ==" % (total_ok, total_fail))
    sys.exit(1 if total_fail else 0)


if __name__ == "__main__":
    main()
