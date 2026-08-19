#!/usr/bin/env bash
# PreToolUse hook (Bash matcher) — ./gradlew 실행 직전 gradle.properties 의 JVM 핀과 $JAVA_HOME 비교.
# stdin: tool_input JSON. exit 2 = block (메시지는 stderr).

set -euo pipefail

input=$(cat)
command=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || true)

# ./gradlew 실행 매칭 — 첫 비-env 토큰이 ./gradlew 인 sub-command 가 있어야 진행.
# 글롭 매칭 (`*"./gradlew "*`) 은 grep/echo/find 인자에 ./gradlew 문자열이 있어도 매치돼
# false-positive 가 발생 (예: `grep ./gradlew README.md` → 루트 차단 메시지).
is_gradlew=$(COMMAND="$command" python3 - <<'PY' 2>/dev/null || true
import os, re, sys
cmd = os.environ.get("COMMAND", "")
parts = re.split(r'(?<!\\)[;&|]+|\s*&&\s*|\s*\|\|\s*', cmd)
for p in parts:
    p = re.sub(r'^\s*\(\s*', '', p.strip())
    toks = p.split()
    i = 0
    while i < len(toks) and re.match(r'^[A-Za-z_][A-Za-z0-9_]*=', toks[i]):
        i += 1
    if i < len(toks) and toks[i] == "./gradlew":
        print("YES")
        sys.exit(0)
print("NO")
PY
)

[[ "$is_gradlew" != "YES" ]] && exit 0

# ROOT 결정: Claude Code 가 주입하는 CLAUDE_PROJECT_DIR 우선.
# onboarding.md Step 5 처럼 모듈 디렉토리에서 hook 을 직접 실행하는 경우 cwd 가 root 가 아니므로,
# fallback 은 스크립트 위치(.agent/hooks/) 기준으로 두 단계 위를 역산해 워크스페이스 루트를 잡는다.
if [[ -n "${CLAUDE_PROJECT_DIR:-}" ]]; then
  ROOT="$CLAUDE_PROJECT_DIR"
else
  ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
CWD="$(pwd)"

# command 의 leading `cd <dir>` 을 파싱해 effective working directory 결정.
# 지원 패턴: "cd <dir> && ./gradlew ...", "cd <dir>; ./gradlew ...", "(cd <dir>; ./gradlew ...)"
# 안전을 위해 첫 토큰이 "cd" 인 경우에만 인식 — 중간에 끼어든 cd 는 무시.
EFFECTIVE_CWD="$CWD"
cd_target=$(printf '%s' "$command" | python3 -c "
import re,sys
c = sys.stdin.read().strip()
# 괄호 서브쉘 제거
c = re.sub(r'^\s*\(\s*', '', c)
m = re.match(r'cd\s+([^\s;&|]+)\s*(?:&&|;)', c)
print(m.group(1) if m else '')
" 2>/dev/null || true)

if [[ -n "$cd_target" ]]; then
  case "$cd_target" in
    /*) EFFECTIVE_CWD="$cd_target" ;;
    *)  EFFECTIVE_CWD="$CWD/$cd_target" ;;
  esac
  # symlink/.. 정규화
  EFFECTIVE_CWD=$(cd "$EFFECTIVE_CWD" 2>/dev/null && pwd || echo "$EFFECTIVE_CWD")
fi

# ROOT 밖 디렉토리 — 차단하지 않고 통과 (사용자가 의도적으로 다른 경로에서 실행)
case "$EFFECTIVE_CWD" in
  "$ROOT"|"$ROOT"/*) ;;
  *) exit 0 ;;
esac

# 요구 JDK 는 루트 gradle.properties 의 undine.jvm 이 SSOT 다.
# 훅에 하드코딩하면 툴체인을 올릴 때 빌드와 가드가 조용히 어긋난다.
required=$(grep -oE '^undine\.jvm[[:space:]]*=[[:space:]]*[0-9]+' "$ROOT/gradle.properties" 2>/dev/null \
  | grep -oE '[0-9]+$' || true)
[[ -z "$required" ]] && exit 0

# 명령이 env 프리픽스로 JAVA_HOME 을 직접 지정했다면 실제 실행 JDK 는 그것이다 — 세션 JAVA_HOME 이 아니라
# 이쪽을 본다. 값이 명령 치환($(...))이면 훅이 평가할 수 없으므로(임의 명령 실행) 명시 지정을 신뢰하고 통과.
java_home_override=$(COMMAND="$command" python3 - <<'PYJH' 2>/dev/null || true
import os, re
cmd = os.environ.get("COMMAND", "")
parts = re.split(r'(?<!\\)[;&|]+|\s*&&\s*|\s*\|\|\s*', cmd)
for part in parts:
    part = re.sub(r'^\s*\(\s*', '', part.strip())
    toks = part.split()
    override, i = "", 0
    while i < len(toks) and re.match(r'^[A-Za-z_][A-Za-z0-9_]*=', toks[i]):
        key, _, value = toks[i].partition("=")
        if key == "JAVA_HOME":
            override = value
        i += 1
    if i < len(toks) and toks[i] == "./gradlew" and override:
        print(override)
        break
PYJH
)

if [[ -n "$java_home_override" ]]; then
  # 평가 불가한 형태(명령 치환·변수 확장)는 명시 지정으로 간주해 검사를 생략한다.
  if [[ "$java_home_override" == *'$'* || "$java_home_override" == *'`'* ]]; then
    exit 0
  fi
  if [[ -x "$java_home_override/bin/java" ]]; then
    JAVA_HOME="$java_home_override"
  fi
fi

# 현재 java -version (JAVA_HOME 우선)
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  current=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "")
else
  current=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "")
fi

if [[ -z "$current" ]]; then
  echo "⚠ Java 실행 파일을 찾을 수 없습니다 (PATH/JAVA_HOME 점검 필요)." >&2
  exit 2
fi

if [[ "$current" != "$required" ]]; then
  cat >&2 <<EOF
🛑 JDK 미스매치: 요구 = JVM $required, 현재 = JVM $current

→ 다음 명령으로 전환 후 재시도:
  export JAVA_HOME=\$(/usr/libexec/java_home -v $required)

JDK 설치 확인: /usr/libexec/java_home -V
EOF
  exit 2
fi

exit 0
