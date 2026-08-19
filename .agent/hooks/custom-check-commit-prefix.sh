#!/usr/bin/env bash
# PreToolUse hook (Bash matcher) — 커밋 메시지 / PR 제목 포맷 게이트.
#   1) git commit 메시지에 UND- 접두사 없으면 차단 (기존).
#      티켓 없는 커밋은 메시지에 `no-ticket` 문자열을 명시하면 접두사 검사만 통과.
#   2) 정밀 lint — 사람이 놓치기 쉬운 소프트 이탈을 로컬에서 차단 —
#      · `<type> :` 콜론 앞 띄어쓰기
#      · 다중 prefix `[UND-01][UND-02]` — 티켓 1개만, 나머지는 본문에 연관 티켓으로
#      정밀 lint 는 `gh pr create --title` 에도 동일 적용 (접두사는 [NO-TICKET] 허용).
#
# 메시지 모드 탐지는 shlex 기반:
#   - `-m foo` / `-m"foo"` (붙임) / `-mfoo` (붙임) / `-am "..."` (short cluster) / `--message foo` / `--message=foo`
#   - 모두 message 모드로 분류. 이전 grep 기반은 `-am` / `-mfix` / `-m"..."` 가 boundary 부재로 통과했음.
# `-F file` / editor 모드는 검사하지 않는다 (훅이 메시지 본문을 볼 수 없다).

set -euo pipefail

input=$(cat)
command=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || true)

[[ -z "$command" ]] && exit 0

verdict=$(COMMAND="$command" python3 - <<'PY' 2>/dev/null || echo "OK"
import os, re, shlex, sys

cmd = os.environ.get("COMMAND", "")
if not cmd:
    print("OK"); sys.exit(0)

try:
    toks = shlex.split(cmd, posix=True)
except ValueError:
    toks = cmd.split()

PREFIX      = re.compile(r"\[UND-[0-9]+\]", re.I)
NOTICKET      = re.compile(r"\[NO-TICKET\]", re.I)
# `] - feat : x` — conventional type 뒤 콜론 앞 공백 (스코프 괄호 허용)
BAD_SPACING = re.compile(r"\]\s*-\s*[A-Za-z]+(\([^)]*\))?\s+:")
MULTI       = re.compile(r"\[UND-[0-9]+\]\s*\[UND-[0-9]+\]", re.I)

def find_sub(cmd_words):
    """toks 에서 연속 서브커맨드 시작 인덱스 (git [-C d] commit / gh pr create)"""
    n = len(toks)
    for i in range(n):
        if toks[i] != cmd_words[0]:
            continue
        j = i + 1
        if cmd_words[0] == "git":
            while j < n and toks[j] in ("-C", "-c") and j + 1 < n:
                j += 2
        ok = True
        for w in cmd_words[1:]:
            if j >= n or toks[j] != w:
                ok = False; break
            j += 1
        if ok:
            return i
    return -1

# ── gh pr create --title 정밀 lint ─────────────────────────────
if find_sub(["gh", "pr", "create"]) >= 0:
    title = None
    for k, t in enumerate(toks):
        if t in ("--title", "-t") and k + 1 < len(toks):
            title = toks[k + 1]; break
        if t.startswith("--title="):
            title = t[len("--title="):]; break
    if title:
        if not (PREFIX.search(title) or NOTICKET.search(title)):
            print("PR_MISSING_PREFIX"); sys.exit(0)
        if MULTI.search(title):
            print("PR_MULTI"); sys.exit(0)
        if BAD_SPACING.search(title):
            print("PR_SPACING"); sys.exit(0)
    print("OK"); sys.exit(0)

# ── git commit 메시지 게이트 ───────────────────────────────────
commit_at = find_sub(["git", "commit"])
if commit_at < 0:
    print("OK"); sys.exit(0)

# commit 이후 토큰 중 message 모드 탐지:
#   - short cluster (-[a-zA-Z]*m...) : -m / -am / -mfix / -m"foo" (붙임 후 shlex 가 -mfoo 형태로 토큰화)
#   - long (--message / --message=...)
short_m = re.compile(r"^-[a-zA-Z]*m")
long_m  = re.compile(r"^--message(=|$)")
msg_mode = any(short_m.match(t) or long_m.match(t) for t in toks[commit_at + 1:])
if not msg_mode:
    # -F file 또는 editor 모드 — pre-commit-msg / PR title CI 가 검증
    print("OK"); sys.exit(0)

# 정밀 lint — no-ticket 커밋에도 적용
if MULTI.search(cmd):
    print("MULTI"); sys.exit(0)
if BAD_SPACING.search(cmd):
    print("SPACING"); sys.exit(0)

# 명시적 no-ticket 우회 (예: "no-ticket: docs only") — 접두사 검사만 면제
if "no-ticket" in cmd.lower():
    print("OK"); sys.exit(0)

# 전체 command 에서 prefix 검색 (heredoc / quote 변형 친화)
if PREFIX.search(cmd):
    print("OK"); sys.exit(0)

print("MISSING_PREFIX")
PY
)

case "$verdict" in
  OK) exit 0 ;;
  SPACING|PR_SPACING)
    cat >&2 <<EOF
🛑 제목/커밋 메시지 포맷 이탈 — \`<type> :\` 콜론 앞 띄어쓰기.

명령: $command

요구 형식: [UND-##] - <type>: <요약>   (콜론은 type 에 붙임 — "feat :" ❌ / "feat:" ✅)
로컬에서 교정 후 재시도.
EOF
    exit 2 ;;
  MULTI|PR_MULTI)
    cat >&2 <<EOF
🛑 다중 티켓 prefix 감지 — [KEY-A][KEY-B] 형식은 컨벤션 이탈.

명령: $command

티켓 키는 1개만 prefix 로: [UND-##] - <type>: <요약>
연관 티켓은 PR 본문(개요)에 링크로 기재.
EOF
    exit 2 ;;
  PR_MISSING_PREFIX)
    cat >&2 <<EOF
🛑 PR 제목 prefix 누락.

명령: $command

요구 형식: [UND-##] - <type>: <요약>  (또는 [NO-TICKET])
EOF
    exit 2 ;;
  *)
    cat >&2 <<EOF
🛑 커밋 메시지 prefix 누락.

명령: $command

요구 형식 중 하나:
  [UND-01] - feat: 짧은 요약
  [UND-14] - fix: ...
  [UND-26] - refactor: ...

- 티켓 없는 작업은 메시지에 'no-ticket' 명시 (예: "no-ticket: docs only").
EOF
    exit 2 ;;
esac
