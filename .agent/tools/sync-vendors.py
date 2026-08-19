#!/usr/bin/env python3
"""`.agent/` (SSOT) → 벤더 디렉토리 투영 생성기.

하네스 자산의 단일 진실 공급원은 `.agent/` 다. 벤더 CLI 는 자기 디렉토리만 탐색하므로,
본문을 손으로 복제하지 않고 **생성**해서 드리프트를 막는다. `.agent/` 를 고친 뒤 이걸 돌린다.

    .agent/tools/sync-vendors.py            # 생성·갱신
    .agent/tools/sync-vendors.py --check    # 최신 여부만 판정 (훅/CI용, 드리프트 시 exit 1)

투영 대상:
    .agent/agents/  → .claude/agents/   (그대로 복사 — Claude Code 가 md frontmatter 로 읽는다)
                    → .codex/agents/    (TOML 변환 — Codex 는 같은 역할을 TOML 로 받는다)
    .agent/skills/  → .claude/skills/   (그대로 복사 — Codex 에는 skill 개념이 없다)
    .agent/rules/   → .claude/rules/    (그대로 복사 — `paths` frontmatter 로 자동 로드된다)

투영하지 않는 것 (벤더 전용이라 손으로 유지):
    .claude/settings.json · .codex/config.toml · .codex/hooks/ · .codex/lib/
    → 이 파일들은 `.agent/hooks/` 의 스크립트를 경로로 가리킨다. 스크립트 자체는 복제되지 않는다.

TOML 변환 매핑:
    name                → name
    description(folded) → description (1줄로 접음)
    본문(frontmatter 뒤) → developer_instructions
    tools               → sandbox_mode (본 레포 에이전트는 전부 읽기 전용이므로 "read-only")
    model / color       → 버림. Claude 별칭(opus 등)은 Codex 모델 id 가 아니고,
                          색상은 Codex 에 대응 개념이 없다. 모델은 호출 시점에 결정한다.
"""

from __future__ import annotations

import argparse
import filecmp
import pathlib
import re
import shutil
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / ".agent"

# (SSOT 디렉토리, 투영 디렉토리) — 내용 변환 없이 그대로 복사한다.
COPY_TARGETS = [
    (".agent/agents", ".claude/agents"),
    (".agent/skills", ".claude/skills"),
    (".agent/rules", ".claude/rules"),
]

CODEX_AGENT_DIR = ".codex/agents"

GENERATED_HEADER = (
    "# 이 파일은 생성됩니다 — 직접 수정하지 마세요.\n"
    "# SSOT: .agent/agents/{source}\n"
    "# 재생성: .agent/tools/sync-vendors.py\n"
)

GENERATED_NOTICE = """# 생성된 디렉토리 — 직접 수정하지 마세요

이 디렉토리의 `agents/` · `skills/` · `rules/` 는 **`.agent/` 에서 생성된 투영본**입니다.
여기서 고친 내용은 다음 `sync-vendors.py` 실행 때 덮어써집니다.

- 편집 대상: `.agent/agents/` · `.agent/skills/` · `.agent/rules/`
- 재생성: `.agent/tools/sync-vendors.py`
- 드리프트 점검: `.agent/tools/sync-vendors.py --check`

손으로 유지하는 파일은 `settings.json` 하나뿐입니다 (Claude Code 전용 스키마 — 훅·권한).
"""


def fail(message: str) -> None:
    print(f"🛑 {message}", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 복사 투영


def relative_files(root: pathlib.Path) -> set[pathlib.Path]:
    return {path.relative_to(root) for path in root.rglob("*") if path.is_file()}


def same_file(source: pathlib.Path, target: pathlib.Path) -> bool:
    if not target.is_file():
        return False
    if (source.stat().st_mode & 0o111) != (target.stat().st_mode & 0o111):
        return False
    return filecmp.cmp(source, target, shallow=False)


def project_copy(source_dir: str, target_dir: str, apply: bool) -> list[str]:
    source_root = REPO_ROOT / source_dir
    target_root = REPO_ROOT / target_dir
    if not source_root.is_dir():
        fail(f"SSOT 디렉토리가 없습니다 — {source_root}")

    changed: list[str] = []
    sources = relative_files(source_root)

    for relative in sorted(sources):
        source = source_root / relative
        target = target_root / relative
        if same_file(source, target):
            continue
        changed.append(f"{target_dir}/{relative}")
        if apply:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    if target_root.is_dir():
        for relative in sorted(relative_files(target_root) - sources):
            changed.append(f"{target_dir}/{relative} (삭제 대상)")
            if apply:
                (target_root / relative).unlink()

    if apply:
        prune_empty_dirs(target_root)
    return changed


def prune_empty_dirs(root: pathlib.Path) -> None:
    if not root.is_dir():
        return
    for path in sorted(root.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        if path.is_dir() and not any(path.iterdir()):
            path.rmdir()


# ---------------------------------------------------------------- TOML 변환 투영


def parse_agent(path: pathlib.Path) -> dict:
    """frontmatter(name/description/tools) + 본문을 뽑는다. PyYAML 없이 처리한다."""
    text = path.read_text()
    match = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not match:
        raise ValueError(f"{path.name}: frontmatter 없음")

    frontmatter, body = match.group(1), text[match.end():]
    fields: dict[str, str] = {}
    current_key: str | None = None

    for line in frontmatter.splitlines():
        header = re.match(r"^([A-Za-z_-]+):\s*(.*)$", line)
        if header and not line.startswith((" ", "\t")):
            current_key = header.group(1)
            value = header.group(2).strip()
            fields[current_key] = "" if value in (">", "|", ">-", "|-") else value
        elif current_key and line.strip():
            # folded 블록의 이어지는 줄
            fields[current_key] = (fields[current_key] + " " + line.strip()).strip()

    if not fields.get("name"):
        raise ValueError(f"{path.name}: name 없음")
    if not fields.get("description"):
        raise ValueError(f"{path.name}: description 없음")

    return {
        "name": fields["name"],
        "description": re.sub(r"\s+", " ", fields["description"]).strip(),
        "tools": fields.get("tools", ""),
        "body": body.strip(),
        "source": path.name,
    }


def toml_basic_string(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def toml_multiline_string(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"""', '""\\"')
    return f'"""\n{escaped}\n"""'


def render_codex_agent(agent: dict) -> str:
    return (
        GENERATED_HEADER.format(source=agent["source"])
        + "\n"
        + f"name = {toml_basic_string(agent['name'])}\n"
        + f"description = {toml_basic_string(agent['description'])}\n"
        # 본 레포 에이전트는 조사·리뷰 전용이다. 쓰기가 필요한 에이전트를 추가하면 여기를 조정한다.
        + 'sandbox_mode = "read-only"\n'
        + f"developer_instructions = {toml_multiline_string(agent['body'])}\n"
    )


def project_codex_agents(apply: bool) -> list[str]:
    source_root = SOURCE_ROOT / "agents"
    target_root = REPO_ROOT / CODEX_AGENT_DIR
    sources = sorted(path for path in source_root.glob("*.md") if path.name != "README.md")
    if not sources:
        fail(f"{source_root} 에 에이전트가 없습니다.")

    changed: list[str] = []
    expected: set[str] = set()

    if apply:
        target_root.mkdir(parents=True, exist_ok=True)

    for source in sources:
        agent = parse_agent(source)
        target = target_root / f"{agent['name']}.toml"
        expected.add(target.name)
        rendered = render_codex_agent(agent)
        if not target.is_file() or target.read_text() != rendered:
            changed.append(f"{CODEX_AGENT_DIR}/{target.name}")
            if apply:
                target.write_text(rendered)

    if target_root.is_dir():
        for path in sorted(target_root.glob("*.toml")):
            if path.name not in expected:
                changed.append(f"{CODEX_AGENT_DIR}/{path.name} (삭제 대상)")
                if apply:
                    path.unlink()

    return changed


# ---------------------------------------------------------------- 안내 파일


def project_notice(apply: bool) -> list[str]:
    target = REPO_ROOT / ".claude/README.md"
    if target.is_file() and target.read_text() == GENERATED_NOTICE:
        return []
    if apply:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(GENERATED_NOTICE)
    return [".claude/README.md"]


# ---------------------------------------------------------------- 드라이버


def main() -> int:
    parser = argparse.ArgumentParser(description=".agent/ → 벤더 디렉토리 투영")
    parser.add_argument("--check", action="store_true", help="최신 여부만 판정 (변경 시 exit 1)")
    args = parser.parse_args()
    apply = not args.check

    changed: list[str] = []
    for source_dir, target_dir in COPY_TARGETS:
        changed += project_copy(source_dir, target_dir, apply)
    changed += project_codex_agents(apply)
    changed += project_notice(apply)

    counts = ", ".join(
        f"{target_dir} {len(relative_files(REPO_ROOT / source_dir))}개"
        for source_dir, target_dir in COPY_TARGETS
    )
    codex_count = len(list((REPO_ROOT / CODEX_AGENT_DIR).glob("*.toml"))) if (REPO_ROOT / CODEX_AGENT_DIR).is_dir() else 0

    if args.check:
        if changed:
            print(f"🛑 벤더 투영이 최신이 아닙니다 ({len(changed)}건):")
            for name in changed[:20]:
                print(f"   - {name}")
            if len(changed) > 20:
                print(f"   ... 외 {len(changed) - 20}건")
            print("   → .agent/tools/sync-vendors.py 실행 후 커밋하세요.")
            return 1
        print(f"✅ 벤더 투영 최신 — {counts}, {CODEX_AGENT_DIR} {codex_count}개")
        return 0

    if changed:
        print(f"갱신 {len(changed)}건")
        for name in changed[:20]:
            print(f"   - {name}")
        if len(changed) > 20:
            print(f"   ... 외 {len(changed) - 20}건")
    else:
        print("변경 없음")
    print(f"투영 결과 — {counts}, {CODEX_AGENT_DIR} {codex_count}개")
    return 0


if __name__ == "__main__":
    sys.exit(main())
