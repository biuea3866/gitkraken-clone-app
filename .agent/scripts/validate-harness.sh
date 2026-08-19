#!/usr/bin/env bash
# validate-harness.sh — 하네스 cross-file 정합 검증 (읽기 전용, CI 비의존)
#
# 목적: "코드/자산 변경 → 카운트·목록을 여러 파일에 수동 동기화" 누락으로 생기는
#       harness drift 를 1-명령으로 탐지한다. (HARNESS.md rubric F⒞·H 의 자동 점검 수단)
#
# 사용: bash .agent/scripts/validate-harness.sh
#   exit 0 = 정합(경고 0) / exit 1 = 불일치 발견
#
# 검사 항목:
#   1) 각 디렉토리 README 헤딩 카운트  ==  실제 파일/디렉토리 수 (agents/skills/hooks/rules)
#   2) settings.json 의 hook 참조  <->  hooks/*.sh 파일  (양방향 일치 + custom- 접두)
#   3) HARNESS.md / onboarding.md 가 가변 카운트(스킬/에이전트/훅/규칙/모듈)를 하드코딩하지 않음
#      (카운트 SSOT 는 각 README 헤딩 — 측정도구가 스스로 drift 원천이 되지 않게)
#   4) wikilink [[...]] 타깃 무결성
#   5) 벤더 투영(.claude/·.codex/) 이 .agent/ SSOT 와 동기 상태인지

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
H="$(dirname "$SCRIPT_DIR")"   # .agent/ — 하네스 SSOT
REPO="$(dirname "$H")"         # 레포 루트
SETTINGS="$REPO/.claude/settings.json"   # 벤더 전용 손유지 파일 (투영 대상 아님)

warn=0
note() { printf '  \033[33m⚠\033[0m  %s\n' "$1"; warn=$((warn + 1)); }
ok()   { printf '  \033[32m✓\033[0m  %s\n' "$1"; }

# README 헤딩의 "(N개)" 숫자 추출
heading_count() { grep -oE '\(([0-9]+)개\)' "$1" 2>/dev/null | head -1 | grep -oE '[0-9]+'; }

echo "== 1. README 카운트 ↔ 실제 파일 수 =="

a_actual=$(find "$H/agents" -maxdepth 1 -name '*.md' ! -name 'README.md' | wc -l | tr -d ' ')
a_doc=$(heading_count "$H/agents/README.md")
[ "$a_actual" = "$a_doc" ] && ok "agents: $a_actual (README $a_doc)" || note "agents: 실제 $a_actual ≠ README '${a_doc}개' — agents/README.md 헤딩 정정"

# 공유 하네스 = git tracked. 로컬 전용 미추적/gitignore 스킬(예: 개인 custom-ask)은 카운트에서 제외.
if git -C "$H" rev-parse >/dev/null 2>&1; then
  skill_dirs=$( (cd "$H" && git ls-files 'skills/*/SKILL.md') | sed 's#/SKILL.md$##; s#^skills/##' )
else
  skill_dirs=$(find "$H/skills" -maxdepth 1 -mindepth 1 -type d -exec basename {} \;)
fi
s_actual=$(printf '%s\n' "$skill_dirs" | sed '/^$/d' | wc -l | tr -d ' ')
s_doc=$(heading_count "$H/skills/README.md")
[ "$s_actual" = "$s_doc" ] && ok "skills: $s_actual (README $s_doc)" || note "skills: 실제 $s_actual ≠ README '${s_doc}개' — skills/README.md 헤딩 정정"

hk_actual=$(find "$H/hooks" -maxdepth 1 -name '*.sh' | wc -l | tr -d ' ')
hk_doc=$(heading_count "$H/hooks/README.md")
[ "$hk_actual" = "$hk_doc" ] && ok "hooks: $hk_actual (README $hk_doc)" || note "hooks: 실제 $hk_actual ≠ README '${hk_doc}개' — hooks/README.md 헤딩 정정"

r_actual=$(find "$H/rules" -maxdepth 1 -name '*.md' ! -name 'README.md' | wc -l | tr -d ' ')
r_doc=$(heading_count "$H/rules/README.md")
[ "$r_actual" = "$r_doc" ] && ok "rules: $r_actual (README $r_doc)" || note "rules: 실제 $r_actual ≠ README '${r_doc}개' — rules/README.md 헤딩 정정"

# 헤딩 숫자만이 아니라 README 본문이 실제로 각 항목을 '나열'하는지(목록 멤버십) 확인 — 행 누락 drift 탐지
# 매칭은 목록(테이블) 행(`| ...`)에서 이름 경계로만 인정한다 — substring 오탐 방지:
#   예) custom-develop 미등재여도 custom-develop-orchestrator 행에 부분 매칭돼 가짜 통과하던 버그.
#   이름 문자([A-Za-z0-9_-])가 양옆에 이어지면 다른 이름으로 보고, 산문 언급(호출 예시 등)은 목록 멤버십으로 치지 않는다.
check_listed() {
  local readme="$1"; shift; local dir it re miss_local=0
  dir=$(basename "$(dirname "$readme")")
  for it in "$@"; do
    re=$(printf '%s' "$it" | sed 's/[][\.*^$()+?{}|]/\\&/g')   # regex 메타문자 이스케이프 ('.sh' 의 '.' 등)
    grep '^|' "$readme" | grep -qE "(^|[^A-Za-z0-9_-])${re}"'([^A-Za-z0-9_-]|$)' \
      || { note "$dir/README 에 '$it' 미등재 (헤딩 카운트는 맞아도 본문 목록에서 빠짐)"; miss_local=$((miss_local + 1)); }
  done
  [ "$miss_local" -eq 0 ] && ok "$dir/README 목록: 전 항목 등재"
}
check_listed "$H/agents/README.md" $(find "$H/agents" -maxdepth 1 -name '*.md' ! -name 'README.md' -exec basename {} .md \;)
check_listed "$H/skills/README.md" $skill_dirs
check_listed "$H/hooks/README.md" $(find "$H/hooks" -maxdepth 1 -name '*.sh' -exec basename {} \;)
check_listed "$H/rules/README.md" $(find "$H/rules" -maxdepth 1 -name '*.md' ! -name 'README.md' -exec basename {} .md \;)

echo "== 2. settings.json hook 참조 ↔ hooks/*.sh =="
ref=$(grep -oE 'custom-[a-z-]+\.sh' "$SETTINGS" | sort -u)
files=$(find "$H/hooks" -maxdepth 1 -name '*.sh' -exec basename {} \; | sort -u)
# 접두 검사
bad_prefix=$(find "$H/hooks" -maxdepth 1 -name '*.sh' ! -name 'custom-*' -exec basename {} \;)
[ -z "$bad_prefix" ] && ok "hook 파일명 모두 custom- 접두" || note "custom- 접두 누락: $bad_prefix"
# 파일인데 settings 미참조
while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "$ref" | grep -qx "$f" || note "hooks/$f 가 settings.json 에서 미참조"
done <<< "$files"
# settings 참조인데 파일 없음
while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "$files" | grep -qx "$f" || note "settings.json 의 $f 파일이 hooks/ 에 없음"
done <<< "$ref"
[ "$(echo "$ref" | grep -c .)" = "$(echo "$files" | grep -c .)" ] && ok "settings ↔ hooks 양방향 일치 ($(echo "$files" | grep -c .)개)"

echo "== 3. HARNESS.md / onboarding.md 가변 카운트 하드코딩 금지 =="
# 금지 패턴 — 한글은 두 어순 + 괄호형, 영문은 두 어순 모두 잡는다:
#   "에이전트 15개" / "규칙(8)" / "15개 에이전트" / "agents (15)" / "13 skills"
PAT='(스킬|에이전트|훅|규칙|모듈) *\(?[0-9]+개?|[0-9]+ *개? *(스킬|에이전트|훅|규칙|모듈)|(skills|agents|hooks|rules|modules) *\([0-9]+\)|[0-9]+ +(skills|agents|hooks|rules|modules)'
for f in "$H/HARNESS.md" "$H/onboarding.md"; do
  hits=$(grep -nEi "$PAT" "$f" 2>/dev/null || true)
  if [ -z "$hits" ]; then
    ok "$(basename "$f"): 하드코딩 카운트 없음"
  else
    note "$(basename "$f"): 가변 카운트 하드코딩 발견 — 디렉토리 README 로 위임"
    echo "$hits" | sed 's/^/        /'
  fi
done

echo "== 4. wikilink [[...]] 타깃 무결성 =="
# 실존 agent/skill/rule 이름 집합 ('name' 은 rules/README 템플릿 placeholder 라 제외)
known_wl=$( { ls "$H"/agents/*.md 2>/dev/null | xargs -n1 basename | sed 's/\.md$//';
              find "$H/skills" -maxdepth 1 -mindepth 1 -type d -exec basename {} \;;
              ls "$H"/rules/*.md 2>/dev/null | xargs -n1 basename | sed 's/\.md$//'; } | sort -u)
wl_bad=0
# 코드펜스와 인라인 코드(`...`) 내부는 제외한다 — TOML 의 [[nodes]] 같은 문법이
# wikilink 로 오탐되지 않게. wikilink 는 산문에 쓰인 것만 링크로 본다.
wl_targets=$(find "$H" -name '*.md' -exec awk '/^```/{fence=!fence; next} !fence' {} + 2>/dev/null \
  | sed 's/`[^`]*`//g' \
  | grep -oE '\[\[[a-z0-9_-]+\]\]' | sed 's/\[\[//;s/\]\]//' | sort -u)
for t in $wl_targets; do
  [ "$t" = "name" ] && continue
  echo "$known_wl" | grep -qx "$t" || { note "broken wikilink [[$t]] — 실존 agent/skill/rule 아님"; wl_bad=$((wl_bad + 1)); }
done
[ "$wl_bad" -eq 0 ] && ok "모든 [[wikilink]] 타깃 실존"

echo "== 5. 벤더 투영(.claude/·.codex/) 동기 상태 =="
sync_out=$("$H/tools/sync-vendors.py" --check 2>&1)
if [ $? -eq 0 ]; then
  ok "$(printf '%s' "$sync_out" | tail -1 | sed 's/^✅ //')"
else
  note "벤더 투영이 .agent/ SSOT 와 어긋남 — .agent/tools/sync-vendors.py 실행 후 커밋"
  printf '%s\n' "$sync_out" | sed 's/^/        /'
fi

echo
if [ "$warn" -eq 0 ]; then
  printf '\033[32m✓ harness 정합: 경고 0\033[0m\n'; exit 0
else
  printf '\033[31m✗ %d개 불일치 — 위 항목 정정 후 재실행\033[0m\n' "$warn"; exit 1
fi
