#!/usr/bin/env python3
"""LinkForge 源码教材 QA 校验器。

用法（仓库根目录）:
    python -I docs/source-learning/tools/check_docs.py

检查项:
  1. 计划清单内的文件全部存在且达到行数下限
  2. Markdown 站内相对链接可解析（http/https/锚点除外）
  3. 课程文件含教学模板 12 个必备小节
  4. ```java 代码块中声明的类型在 src/ 下真实存在（可用围栏标记 ignore-check 豁免示意代码）
  5. 无占位符（TODO/TBD/待补/占位/FIXME）
  6. Archify 图表: 源 JSON + HTML + finalize 回执齐全

退出码 0 = 全绿。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]      # docs/source-learning
REPO = BASE.parents[1]                          # 仓库根
SRC_DIRS = [REPO / "src/main/java", REPO / "src/test/java"]

LESSONS: dict[str, int] = {
    "modules/01-spring-boot/01-maven-pom.md": 150,
    "modules/01-spring-boot/02-bootstrap-ioc-di.md": 150,
    "modules/01-spring-boot/03-configuration-profiles.md": 150,
    "modules/02-common/01-unified-response-exceptions.md": 150,
    "modules/02-common/02-logging-utils-clientip.md": 120,
    "modules/03-spring-mvc/01-controllers-rest.md": 150,
    "modules/03-spring-mvc/02-request-lifecycle-filters.md": 150,
    "modules/04-mybatis-mysql/01-entities-mappers-crud.md": 150,
    "modules/04-mybatis-mysql/02-flyway-migrations.md": 150,
    "modules/04-mybatis-mysql/03-transactions.md": 150,
    "modules/05-security/01-security-filter-chain.md": 150,
    "modules/05-security/02-jwt-auth.md": 150,
    "modules/05-security/03-usercontext-authz.md": 150,
    "modules/06-short-link/01-data-model.md": 150,
    "modules/06-short-link/02-create-flow.md": 200,
    "modules/06-short-link/03-crud-lifecycle-csv.md": 150,
    "modules/06-short-link/04-qr-utm-materials.md": 150,
    "modules/07-redirection/01-decision-chain.md": 200,
    "modules/07-redirection/02-password-and-domain-gates.md": 150,
    "modules/07-redirection/03-variants-split.md": 150,
    "modules/08-redis-cache/01-keys-and-ttl.md": 150,
    "modules/08-redis-cache/02-l1-l2-write-through.md": 150,
    "modules/08-redis-cache/03-negative-cache-bloom.md": 150,
    "modules/08-redis-cache/04-singleflight.md": 150,
    "modules/09-rate-limit/01-sliding-window-lua.md": 150,
    "modules/09-rate-limit/02-idempotency-and-quotas.md": 150,
    "modules/10-rabbitmq-outbox/01-topology-confirm.md": 150,
    "modules/10-rabbitmq-outbox/02-outbox-relay.md": 150,
    "modules/10-rabbitmq-outbox/03-consumer-idempotency-dlq.md": 150,
    "modules/11-statistics/01-click-record-pipeline.md": 150,
    "modules/11-statistics/02-aggregation-dimensions.md": 150,
    "modules/11-statistics/03-stats-api-reconciler.md": 150,
    "modules/12-api-key/01-apikey-full.md": 180,
    "modules/13-webhook/01-endpoints-and-cipher.md": 150,
    "modules/13-webhook/02-delivery-signing-retry.md": 150,
    "modules/13-webhook/03-ssrf-and-aggregate.md": 150,
    "modules/14-observability/01-actuator-micrometer.md": 150,
    "modules/14-observability/02-metrics-gauges-grafana.md": 150,
    "modules/14-observability/03-scheduled-tasks-map.md": 150,
    "modules/15-testing-deployment/01-test-pyramid.md": 150,
    "modules/15-testing-deployment/02-maven-ci.md": 150,
    "modules/15-testing-deployment/03-compose-and-drills.md": 150,
}

FOUNDATION: dict[str, int] = {
    "00-README.md": 80,
    "01-project-overview.md": 150,
    "02-architecture-map.md": 200,
    "03-curriculum-roadmap.md": 150,
}

ADVANCED: dict[str, int] = {
    "advanced/01-registration-login.md": 150,
    "advanced/02-create-short-link.md": 180,
    "advanced/03-short-link-management.md": 150,
    "advanced/04-redirect-302.md": 180,
    "advanced/05-cache-hit-and-miss.md": 150,
    "advanced/06-password-unlock.md": 150,
    "advanced/07-click-record-outbox.md": 150,
    "advanced/08-mq-consume-idempotency.md": 150,
    "advanced/09-stats-reconcile.md": 150,
    "advanced/10-api-key-auth.md": 150,
    "advanced/11-webhook-delivery-retry.md": 150,
    "advanced/12-dependency-failure-handling.md": 150,
    "advanced/13-performance-and-consistency.md": 150,
    "advanced/14-interview-guide.md": 200,
}

REFERENCE: dict[str, int] = {
    "reference/class-coverage-matrix.md": 80,
    "reference/feature-coverage-matrix.md": 80,
    "reference/diagram-coverage-matrix.md": 60,
    "reference/technical-glossary.md": 150,
    "reference/code-navigation-index.md": 80,
    "reference/progress.md": 40,
    "reference/issues-and-discrepancies.md": 40,
}

DIAGRAMS = [
    "00-system-architecture", "01-runtime-deployment", "02-function-map",
    "03-package-map", "04-core-class-relations", "05-database-erd",
    "06-redis-data-flow", "07-rabbitmq-topology", "08-scheduled-tasks",
    "09-test-ci-pipeline", "10-auth-sequence", "11-shortlink-create",
    "12-redirect-decision", "13-cache-hit-miss-sequence", "14-click-pipeline-dataflow",
    "15-outbox-lifecycle", "16-webhook-delivery-lifecycle",
]

TEMPLATE_SECTIONS = [
    "学习目标", "前置知识", "本节涉及的源码", "类的定位", "核心源码详解",
    "技术原理补充", "可视化", "项目中的真实执行过程", "为什么这样设计",
    "本节总结", "课后练习", "与后续课程的联系",
]

JAVA_DECL = re.compile(r"\b(?:class|interface|record|enum)\s+([A-Z][A-Za-z0-9_]*)")
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
FENCE = re.compile(r"^```(.*)$")
PLACEHOLDER = re.compile(r"TODO|TBD|FIXME|待补|占位符|【待")


def all_java_types() -> set[str]:
    """顶层文件名 + 源码内声明的嵌套类型（如 ImportResponse.RowSuccess）。"""
    types: set[str] = set()
    for d in SRC_DIRS:
        for f in d.rglob("*.java"):
            types.add(f.stem)
            try:
                types.update(JAVA_DECL.findall(f.read_text(encoding="utf-8")))
            except UnicodeDecodeError:
                pass
    return types


def check_file(path: Path, min_lines: int, errors: list[str], java_types: set[str]) -> None:
    rel = path.relative_to(BASE).as_posix()
    if not path.exists():
        errors.append(f"[缺失] {rel}")
        return
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        errors.append(f"[编码] {rel} 不是 UTF-8")
        return
    lines = text.splitlines()
    if len(lines) < min_lines:
        errors.append(f"[行数] {rel} {len(lines)} < {min_lines}")
    for m in PLACEHOLDER.finditer(text):
        ctx = text[max(0, m.start() - 30): m.end() + 30].replace("\n", " ")
        errors.append(f"[占位] {rel}: …{ctx}…")
    for m in LINK.finditer(text):
        target = m.group(1)
        if target.startswith(("http://", "https://", "#", "mailto:")):
            continue
        clean = target.split("#", 1)[0]
        if not clean:
            continue
        resolved = (path.parent / clean).resolve()
        if not resolved.exists():
            errors.append(f"[链接] {rel} -> {target}")
    # 课程模板节检查（仅 modules/ 下的课程文件）
    if rel.startswith("modules/"):
        missing = [s for s in TEMPLATE_SECTIONS if s not in text]
        if missing:
            errors.append(f"[模板缺节] {rel}: {', '.join(missing)}")
    # java 代码块类型检查
    in_fence = False
    idx = 0
    while idx < len(lines):
        fm = FENCE.match(lines[idx].strip())
        if fm and not in_fence:
            in_fence, info, buf = True, fm.group(1), []
            idx += 1
            while idx < len(lines) and not FENCE.match(lines[idx].strip()):
                buf.append(lines[idx])
                idx += 1
            if idx < len(lines):
                in_fence = False
            info_l = info.strip()
            if info_l.startswith("java") and "ignore-check" not in info_l:
                for dm in JAVA_DECL.finditer("\n".join(buf)):
                    if dm.group(1) not in java_types:
                        errors.append(f"[类型不存在] {rel}: {dm.group(1)}")
        idx += 1


def check_diagrams(errors: list[str]) -> None:
    for slug in DIAGRAMS:
        src = BASE / f"diagrams/archify/source/{slug}.json"
        html = BASE / f"diagrams/archify/html/{slug}.html"
        for p in (src, html):
            if not p.exists():
                errors.append(f"[图表缺失] {p.relative_to(BASE).as_posix()}")
        if src.exists():
            try:
                json.loads(src.read_text(encoding="utf-8"))
            except json.JSONDecodeError as e:
                errors.append(f"[图表 JSON] {src.name}: {e}")
        if html.exists():
            receipt = BASE / f"diagrams/archify/html/{slug}.finalize-summary.json"
            if not receipt.exists():
                errors.append(f"[图表回执] {slug}: 缺少 finalize-summary.json")
            else:
                try:
                    summary = json.loads(receipt.read_text(encoding="utf-8"))
                    if summary.get("ok") is not True or summary.get("status") != "pass":
                        failed = summary.get("failedStage", "unknown")
                        errors.append(f"[图表回执] {slug}: finalize 未通过（failedStage={failed}）")
                    else:
                        import hashlib
                        artifact = summary.get("artifact") or {}
                        expected = artifact.get("sha256")
                        if expected:
                            actual = hashlib.sha256(html.read_bytes()).hexdigest()
                            if actual != expected:
                                errors.append(f"[图表回执] {slug}: HTML 与回执 sha256 不一致（HTML 可能被改动）")
                except json.JSONDecodeError as e:
                    errors.append(f"[图表回执] {slug}: 回执不是合法 JSON: {e}")


def main() -> int:
    java_types = all_java_types()
    errors: list[str] = []
    for group in (FOUNDATION, LESSONS, ADVANCED, REFERENCE):
        for rel, floor in group.items():
            check_file(BASE / rel, floor, errors, java_types)
    check_diagrams(errors)
    if errors:
        print(f"FAIL: {len(errors)} 项问题")
        for e in errors:
            print("  " + e)
        return 1
    total = sum(len(g) for g in (FOUNDATION, LESSONS, ADVANCED, REFERENCE)) + len(DIAGRAMS)
    print(f"OK: {total} 个计划产物全部通过校验")
    return 0


if __name__ == "__main__":
    sys.exit(main())
