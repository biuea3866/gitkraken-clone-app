#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|MultiEdit matcher) — Kotlin 파일 편집 후 해당 모듈의 detekt 실행 리마인더.
# 차단 없음. Claude 컨텍스트에 system-reminder 로 주입한다.
# 11+ 모듈이 각자 detekt.yml 을 갖고 pre-commit hook 에서 자동 실행되므로,
# commit 직전 실패를 미연에 방지하기 위한 가이드.
#
# PostToolUse 의 plain stdout 은 debug log 에만 기록되고 Claude 컨텍스트에 도달하지 않는다.
# (공식 문서: SessionStart/UserPromptSubmit/UserPromptExpansion 만 plain stdout → context 주입.)
# 따라서 본 훅은 {"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"..."}}
# JSON 을 출력해야 Claude 가 메시지를 읽고 ./gradlew detekt 를 사전 실행할 수 있다.

set -euo pipefail

input=$(cat)
file=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('file_path') or d.get('path',''))" 2>/dev/null || true)
hook_cwd=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.loads(sys.stdin.read() or '{}').get('cwd',''))" 2>/dev/null || true)

# Kotlin 소스만 대상 (.kt / .kts). 테스트 코드 포함.
case "$file" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

# 절대경로 → 워크스페이스 상대경로
# CLAUDE_PROJECT_DIR (Claude Code 주입) 우선, 미설정 시 스크립트 위치(.agent/hooks/) 기준 역산.
# 직접 호출 시에도 동일하게 동작하도록 한다.
if [[ -n "${CLAUDE_PROJECT_DIR:-}" ]]; then
  ws="$CLAUDE_PROJECT_DIR"
else
  ws="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

# file 이 상대경로인 경우 hook stdin.cwd 또는 process pwd 기준으로 절대화한다.
# (Claude Code 는 보통 absolute file_path 를 주지만, 일부 도구·테스트 호출에서 relative 가 올 수 있다.)
case "$file" in
  /*) ;;  # already absolute
  *)
    base="${hook_cwd:-$(pwd)}"
    file="$base/$file"
    ;;
esac
rel="${file#$ws/}"

# 모듈 디렉토리 = 워크스페이스 직하의 첫 번째 디렉토리
module="${rel%%/*}"

# detekt.yml 또는 config/detekt/detekt.yml 보유한 모듈만 안내
if [[ -f "$ws/$module/detekt.yml" ]] \
   || [[ -f "$ws/$module/config/detekt/detekt.yml" ]] \
   || [[ -f "$ws/$module/.config/detekt/detekt.yml" ]]; then
  REL="$rel" MODULE="$module" python3 <<'PY'
import json, os
rel = os.environ.get("REL", "")
module = os.environ.get("MODULE", "")
msg = f"""Kotlin 파일이 변경되었습니다: {rel}
모듈: {module} — pre-commit hook 에서 detekt 가 자동 실행됩니다.

커밋 전 사전 실행 권장:
  cd {module} && ./gradlew detekt

baseline 갱신이 필요한 경우 (신규 룰 위반은 즉시 수정 우선):
  cd {module} && ./gradlew detektBaseline

상세 룰셋: {module}/detekt.yml (또는 {module}/config/detekt/detekt.yml)"""
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": msg,
    }
}))
PY
fi

exit 0
