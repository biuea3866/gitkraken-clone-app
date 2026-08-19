#!/usr/bin/env bash
# PreToolUse hook (Edit|Write|MultiEdit matcher) — 생성된 벤더 투영 편집 차단.
#
# `.claude/{agents,skills,rules}` 와 `.codex/agents` 는 `.agent/` 에서 생성된 투영본이다.
# 여기를 고치면 다음 `sync-vendors.py` 실행에 조용히 덮어써진다 — 그래서 차단(exit 2)한다.
# 손유지 파일(.claude/settings.json · .codex/config.toml · .codex/hooks · .codex/lib)은 통과.

set -euo pipefail

input=$(cat)
file=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('file_path') or d.get('path',''))" 2>/dev/null || true)

[[ -z "$file" ]] && exit 0

# 절대 경로를 레포 상대 경로로 정규화 (프로젝트 루트 밖 편집은 이 훅의 관심사가 아니다).
root="${CLAUDE_PROJECT_DIR:-$PWD}"
relative="${file#"$root"/}"

case "$relative" in
  .claude/agents/*|.claude/skills/*|.claude/rules/*|.claude/README.md|.codex/agents/*)
    ssot=$(printf '%s' "$relative" | sed -e 's|^\.claude/|.agent/|' -e 's|^\.codex/agents/|.agent/agents/|' -e 's|\.toml$|.md|')
    cat >&2 <<EOF
🛑 생성된 벤더 투영 편집 차단됨.

파일: $relative

이 경로는 \`.agent/\` 에서 생성되는 투영본입니다 — 여기서 고치면 다음 재생성에 덮어써집니다.

→ SSOT 를 고치세요: $ssot
→ 그 다음 재생성: .agent/tools/sync-vendors.py

손유지 파일은 예외입니다: .claude/settings.json · .codex/config.toml · .codex/hooks/ · .codex/lib/
EOF
    exit 2
    ;;
esac

exit 0
