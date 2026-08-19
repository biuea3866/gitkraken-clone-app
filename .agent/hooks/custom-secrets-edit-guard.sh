#!/usr/bin/env bash
# PreToolUse hook (Edit|Write matcher) — 민감한 파일/경로 편집 시 확인 요청.
# 차단이 아니라 사용자 인지(stderr) — 정당한 경우 재시도하면 통과 (exit 0).

set -euo pipefail

input=$(cat)
file=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('file_path') or d.get('path',''))" 2>/dev/null || true)

[[ -z "$file" ]] && exit 0

# 1) 비밀 파일 편집 차단. .claude-local/ 은 팀원 개인 용도라 제외 (안의 .env 등은 별도 패턴이 잡음)
# 샘플/템플릿 (.env.example / .env.sample / .env.template) 은 git tracked 온보딩 자산이라 허용
case "$file" in
  */.env.example|*/.env.sample|*/.env.template|.env.example|.env.sample|.env.template)
    exit 0
    ;;
esac

# SSH 키 변종(.pub/.backup/.old/_personal + dsa/ecdsa/ed448) 은 [[ =~ ]] 정규식으로 통합 처리.
if [[ "$file" =~ (^|/)id_(rsa|dsa|ecdsa|ed25519|ed448)([._][A-Za-z0-9._\-]*)?$ ]]; then
  cat >&2 <<EOF
🛑 비밀 파일 편집 차단됨.

파일: $file

이 파일은 자격증명/키를 포함할 수 있어 Claude 가 직접 편집할 수 없습니다.
사용자에게 변경 의도를 보고하고, 사용자가 직접 편집하도록 안내하세요.
(참고: .claude-local/ 는 팀원 개인 용도로 차단 대상에서 제외됩니다.)
EOF
  exit 2
fi

case "$file" in
  */.env|*/.env.*|.env|.env.*|*/credentials.json|*/secrets.yml|*/secrets.yaml|*/secrets.json|*.pem|*.p12|*.pfx|*.keystore|*.jks)
    cat >&2 <<EOF
🛑 비밀 파일 편집 차단됨.

파일: $file

이 파일은 자격증명/키를 포함할 수 있어 Claude 가 직접 편집할 수 없습니다.
사용자에게 변경 의도를 보고하고, 사용자가 직접 편집하도록 안내하세요.
(참고: .claude-local/ 는 팀원 개인 용도로 차단 대상에서 제외됩니다.)
EOF
    exit 2
    ;;
esac

# 2) application-*.yml/.yaml/.properties 파일에 password|secret|token|api[_-]?key 패턴 변경 — 경고만 (stderr)
case "$file" in
  *application-*.yml|*application-*.yaml|*application-*.properties|*application.yml|*application.yaml|*application.properties)
    new_string=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('new_string') or d.get('content',''))" 2>/dev/null || true)
    if printf '%s' "$new_string" | grep -qiE '(password|secret|token|api[_-]?key|private[_-]?key|access[_-]?key)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9]'; then
      cat >&2 <<EOF
⚠ application 설정 파일에 비밀 값으로 보이는 패턴이 포함됨.

파일: $file

→ 자격증명은 OS 키체인 또는 환경 변수로 주입하는 것이 본 프로젝트 컨벤션입니다.
→ \${VAR_NAME} 형식의 placeholder 인지 확인하세요. 평문 시크릿을 커밋하지 마세요.

(이 훅은 차단이 아닙니다. 의도된 변경이면 그대로 진행하세요.)
EOF
      # advisory only — 통과
      exit 0
    fi
    ;;
esac

exit 0
