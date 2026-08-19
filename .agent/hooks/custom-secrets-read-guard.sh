#!/usr/bin/env bash
# PreToolUse hook (Read matcher) — Claude 의 Read 도구로 .env / 자격증명 파일을 읽는 것을 차단.
# permissions.deny 와 이중 보호.

set -euo pipefail

input=$(cat)
file=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('file_path') or d.get('path',''))" 2>/dev/null || true)

[[ -z "$file" ]] && exit 0

# 차단 패턴 — 대소문자 무시. .claude-local/ 은 팀원 개인 용도라 제외 (안의 .env 는 별도로 잡힘).
# SSH 키 변종(`.pub`, `.backup`, `.old`, `_personal` 등 + dsa/ecdsa/ed448) 은 [[ =~ ]] 정규식으로
# 따로 처리한다 — case 의 glob 만으로는 `id_rsa_personal` / `id_ed25519.pub` 등을 일관 커버하기 어렵다.
shopt -s nocasematch || true
blocked=0
if [[ "$file" =~ (^|/)id_(rsa|dsa|ecdsa|ed25519|ed448)([._][A-Za-z0-9._\-]*)?$ ]]; then
  blocked=1
else
  case "$file" in
    # 샘플/템플릿은 git tracked 온보딩 자산이라 허용
    */.env.example|*/.env.sample|*/.env.template|.env.example|.env.sample|.env.template) blocked=0 ;;
    */.env|*/.env.*|.env|.env.*) blocked=1 ;;
    */credentials.json|credentials.json) blocked=1 ;;
    */secret.yml|*/secret.yaml|*/secrets.yml|*/secrets.yaml|*/secrets.json) blocked=1 ;;
    *.pem|*.p12|*.pfx|*.keystore|*.jks) blocked=1 ;;
    *) blocked=0 ;;
  esac
fi
shopt -u nocasematch || true

if [[ "$blocked" == "1" ]]; then
  cat >&2 <<EOF
🛑 비밀 파일 읽기 차단됨.

파일: $file

본 워크스페이스 정책:
- .env / .env.* / credentials.json / secrets.{yml,yaml,json} / *.pem|*.p12|*.pfx|*.keystore|*.jks
  / id_(rsa|dsa|ecdsa|ed25519|ed448) (.pub/.backup/.old/_personal 등 변종 포함)
  는 Claude 의 Read 도구로 열 수 없습니다.
- (참고: .claude-local/ 는 팀원 개인 용도로 차단 대상에서 제외됩니다.)
- 값이 conversation 컨텍스트에 들어가면 로그·캐시·미래 세션에 잔존합니다.

→ 변경이 필요하면 사용자가 직접 편집하세요.
→ 어떤 키/변수가 들어있는지만 알고 싶으면 키 목록(\$\$ values 제외)을 사용자에게 요청.
→ 이 가드는 우회할 수 없습니다 (Bash 측은 custom-block-env-read.sh, 설정 측은 permissions.deny).
EOF
  exit 2
fi

exit 0
