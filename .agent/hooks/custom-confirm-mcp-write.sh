#!/usr/bin/env bash
# PreToolUse hook (MCP matcher) — 외부 상태를 바꾸는 MCP 도구 호출 직전에 사용자 승인 프롬프트를 띄운다.
# 가드 대상:
#   - claude.ai connector: Slack / GitHub / Gmail / Google Calendar / Google Drive / Figma
# 정책: 매칭 시 `permissionDecision="ask"` JSON 응답으로 Claude Code 가 사용자에게 호출 승인을 요청한다.
#       사용자가 본문/대상/영향을 검토한 뒤 Approve 하면 Claude 가 본 세션에서 직접 실행하고,
#       Deny 하면 도구 호출이 취소되며 Claude 의 자동 재시도도 다시 승인 프롬프트를 통과해야 한다.
# 사고 시나리오: Claude 가 컨텍스트 흐름에서 자동으로 메시지/이슈/PR 을 만들어 외부에 영향을 주는 것을 가시화.
#
# 매처 정책: connector / MCP 서버가 새 write 도구를 추가해도 커버하도록 verb 기반 prefix 매칭을 사용한다.
# Slack 은 read/search 외 모든 도구가 외부 영향을 줄 수 있어 broad 매치 + read/search allowlist 로 처리.

set -euo pipefail

input=$(cat)
tool_name=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_name',''))" 2>/dev/null || true)

label=""
case "$tool_name" in
  # Slack: read_* / search_* 만 read-only 로 통과, 그 외는 모두 차단.
  mcp__claude_ai_Slack__slack_read_*|mcp__claude_ai_Slack__slack_search_*)
    exit 0 ;;
  mcp__claude_ai_Slack__*)
    label="Slack 메시지/캔버스 변경" ;;
  # GitHub connector: read 동사(get_/list_/search_/find_) 와 OAuth 만 통과, 그 외는 모두 ask.
  # GitHub MCP 는 issue/PR/comment/file/branch/release write 도구가 많아 enumeration 보다 broad 차단이 안전.
  # connector 도구 명세가 바뀌어도 신규 write 가 자동 ask 로 잡힘.
  mcp__claude_ai_GitHub__get_*|\
  mcp__claude_ai_GitHub__list_*|\
  mcp__claude_ai_GitHub__search_*|\
  mcp__claude_ai_GitHub__find_*|\
  mcp__claude_ai_GitHub__authenticate*|\
  mcp__claude_ai_GitHub__complete_authentication*)
    exit 0 ;;
  mcp__claude_ai_GitHub__*)
    label="GitHub Issue/PR/Comment/Repo 변경" ;;
  mcp__claude_ai_Gmail__create*|\
  mcp__claude_ai_Gmail__update*|\
  mcp__claude_ai_Gmail__delete*|\
  mcp__claude_ai_Gmail__label*|\
  mcp__claude_ai_Gmail__unlabel*|\
  mcp__claude_ai_Gmail__send*|\
  mcp__claude_ai_Gmail__trash*|\
  mcp__claude_ai_Gmail__reply*|\
  mcp__claude_ai_Gmail__forward*)
    label="Gmail 라벨/드래프트/발송 변경" ;;
  mcp__claude_ai_Google_Calendar__create*|\
  mcp__claude_ai_Google_Calendar__update*|\
  mcp__claude_ai_Google_Calendar__delete*|\
  mcp__claude_ai_Google_Calendar__respond*|\
  mcp__claude_ai_Google_Calendar__move*|\
  mcp__claude_ai_Google_Calendar__share*)
    label="Google Calendar 이벤트 변경" ;;
  mcp__claude_ai_Google_Drive__create*|\
  mcp__claude_ai_Google_Drive__update*|\
  mcp__claude_ai_Google_Drive__delete*|\
  mcp__claude_ai_Google_Drive__copy*|\
  mcp__claude_ai_Google_Drive__move*|\
  mcp__claude_ai_Google_Drive__share*|\
  mcp__claude_ai_Google_Drive__upload*|\
  mcp__claude_ai_Google_Drive__trash*|\
  mcp__claude_ai_Google_Drive__rename*)
    label="Google Drive 파일 변경" ;;
  # Figma — 디자인 파일 생성/수정/업로드 등 write 동사 차단.
  # read 계열(get_*/search_*/whoami/use_figma 의 read 모드 등)은 통과.
  # use_figma 는 JavaScript 실행으로 write 가능하므로 매처에 포함한다.
  mcp__claude_ai_Figma__create*|\
  mcp__claude_ai_Figma__update*|\
  mcp__claude_ai_Figma__delete*|\
  mcp__claude_ai_Figma__upload*|\
  mcp__claude_ai_Figma__add*|\
  mcp__claude_ai_Figma__send*|\
  mcp__claude_ai_Figma__use*|\
  mcp__claude_ai_Figma__generate*)
    label="Figma 디자인 파일 변경" ;;
  *)
    exit 0 ;;
esac

reason="🔔 ${label} 승인 요청 — ${tool_name}

본 프로젝트 정책 (.agent/onboarding.md):
- 외부 시스템 변경 (Slack 발송 / GitHub Issue·PR·Comment·File /
  Gmail·Calendar·Drive 쓰기 / Figma 디자인 파일 변경) 은
  Claude 직접 호출 전 사용자 승인이 필요합니다.

승인 전 확인할 항목:
- 대상 (채널/레포/이슈·PR 번호/캘린더 등)
- 본문 (PII/비밀 미포함)
- 영향 (수신자 / 자동화 효과 / 변경 범위)

Deny 시 Claude 의 자동 재시도는 다시 본 승인 프롬프트를 통과해야 합니다.

정책 변경이 필요하면 PR 로 .claude/settings.json matcher 또는 본 훅을 갱신."

python3 -c "
import json, sys
print(json.dumps({
  'hookSpecificOutput': {
    'hookEventName': 'PreToolUse',
    'permissionDecision': 'ask',
    'permissionDecisionReason': sys.argv[1]
  }
}, ensure_ascii=False))
" "$reason"
exit 0
