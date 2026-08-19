#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|MultiEdit matcher, async) — .kt/.kts 변경 시 해당 모듈 detekt 자동 실행.
# 정책: 변경 모듈 한정 + async(settings.json "async": true — Claude 를 블로킹하지 않음).
#       detekt "실행 안내"(custom-detekt-touch-reminder.sh)와 분리된 "집행" 레이어 — 점진 도입.
#       실행 불가 조건(모듈 미식별·detekt 미적용·gradle/JAVA_HOME 환경 실패)은 조용히 skip 해 오발화를 막고,
#       detekt 위반이 실제 검출된 경우에만 additionalContext 로 보고한다.
set -euo pipefail

input=$(cat)
file_path=$(printf '%s' "$input" | python3 -c "import json,sys;d=json.load(sys.stdin).get('tool_input',{});print(d.get('file_path') or d.get('path') or '')" 2>/dev/null || true)

case "$file_path" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac
[[ -f "$file_path" ]] || exit 0
case "$file_path" in
  */build/*|*/buildSrc/*) exit 0 ;;
esac

# 가장 가까운 gradlew 보유 디렉토리 = 프로젝트 루트
dir=$(cd "$(dirname "$file_path")" && pwd)
module_root=""
while [[ "$dir" != "/" ]]; do
  if [[ -x "$dir/gradlew" ]]; then module_root="$dir"; break; fi
  dir=$(dirname "$dir")
done
[[ -n "$module_root" ]] || exit 0

# detekt 적용 모듈만 (모듈 루트 build.gradle(.kts) 또는 buildSrc 에서 탐지)
if ! grep -qs "detekt" "$module_root/build.gradle.kts" "$module_root/build.gradle" 2>/dev/null \
   && ! grep -rqs "detekt" "$module_root/buildSrc/src" 2>/dev/null; then
  exit 0
fi

# 디바운스: 동일 모듈 5분 내 재실행 방지 (연속 편집 중 gradle 반복 기동 방지)
stamp="${TMPDIR:-/tmp}/claude-detekt-$(printf '%s' "$module_root" | cksum | cut -d' ' -f1)"
now=$(date +%s)
last=$(cat "$stamp" 2>/dev/null || echo 0)
if [[ $((now - last)) -lt 300 ]]; then
  exit 0
fi
printf '%s' "$now" > "$stamp"

# 실행 (macOS 에 timeout 부재 가능 — 있으면 5분 상한)
runner=(./gradlew -q detekt)
if command -v timeout >/dev/null 2>&1; then
  runner=(timeout 300 ./gradlew -q detekt)
fi
out=$(cd "$module_root" && "${runner[@]}" 2>&1 || true)

# detekt 위반 라인(file.kt:line:col: message)만 보고 — 빌드/환경 실패는 조용히 skip
violations=$(printf '%s\n' "$out" | grep -E '\.kts?:[0-9]+:[0-9]+:' | head -20 || true)
[[ -n "$violations" ]] || exit 0

python3 -c "
import json, sys
print(json.dumps({
  'hookSpecificOutput': {
    'hookEventName': 'PostToolUse',
    'additionalContext': 'detekt 위반 감지 — 모듈 ' + sys.argv[1] + ' (상위 20건):\n' + sys.argv[2]
      + '\n→ 수정 후 해당 모듈에서 ./gradlew detekt 재확인 (JAVA_HOME 은 custom-module-router 참조)'
  }
}, ensure_ascii=False))
" "$module_root" "$violations"
exit 0
