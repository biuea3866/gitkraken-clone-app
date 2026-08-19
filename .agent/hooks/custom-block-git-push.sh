#!/usr/bin/env bash
# PreToolUse hook (Bash matcher) — git push 직전 사용자 승인 프롬프트(ask).
# 정책: 매칭 시 `permissionDecision="ask"` JSON 응답으로 Claude Code 가 사용자에게 호출 승인을 요청한다.
#       Approve → Claude 가 본 세션에서 직접 push 실행, Deny → 호출 취소 (자동 재시도도 다시 ask 프롬프트 통과 필요).
# main/master 로의 force push 등 위험 옵션은 reason 메시지에 표기해 사용자가 검토 후 판단하도록 유도.

set -euo pipefail

input=$(cat)
command=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || true)

if ! [[ "$command" =~ (^|[[:space:]\;])git([[:space:]]+-[Cc][[:space:]]+[^[:space:]]+)?[[:space:]]+push([[:space:]]|$) ]]; then
  exit 0
fi

force_note=""
if [[ "$command" =~ --force-with-lease ]]; then
  force_note="
⚠️ --force-with-lease 감지 — 원격 변경 덮어쓰기 가능. 푸시 대상 브랜치 확인 필수."
elif [[ "$command" =~ --force|[[:space:]]-f([[:space:]]|$) ]]; then
  force_note="
🛑 --force / -f 감지 — main/master 로의 force push 는 절대 금지. 다른 브랜치라도 협업자 변경 덮어쓸 수 있음."
fi

reason="🔔 git push 승인 요청

명령: ${command}
${force_note}

승인 전 확인할 항목:
- 현재 브랜치 / 푸시 대상 remote/branch
- 푸시될 커밋 목록 (git log --oneline origin/<branch>..HEAD)
- --force / --force-with-lease 사용 여부와 이유

🧪 Self code review 완료했나요?
- 미수행 시 Deny 하고 다음 턴에 \`/custom-self-code-review\` 호출 권장 (본 push 시도는 취소됨)
  · 5축 점검: 의도/티켓 정합성 · 테스트 커버 · 사이드 이펙트 · 배포 의존성 · 롤백 가능성
  · 변경 모듈이 ci.yml paths 미커버(테스트 CI 없음)라면 \`/custom-affected-test-runner\` 로 로컬 테스트 후 push
  · push 후에는 PR open → \`/custom-pr-create\` 으로 본문 작성

Approve → Claude 가 직접 실행, Deny → 호출 취소 (자동 재시도도 다시 본 프롬프트 통과)."

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
