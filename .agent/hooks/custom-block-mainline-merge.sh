#!/usr/bin/env bash
# PreToolUse hook (Bash matcher) — 보호 브랜치 위에서의 머지/리베이스를 차단.
# 사고 패턴: Claude 가 `gh pr merge` 또는 main/stage-* 위에서 `git merge feature` 류를 실수로 실행
# → 잘못된 base 의 PR 머지 / 로컬 보호 브랜치 오염.
# custom-block-git-push.sh 의 자매 훅 — push 와 별개로 머지 동사도 가드.

set -euo pipefail

input=$(cat)
command=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || true)
protected_branch_re='^(main|stage-[A-Za-z0-9._-]+)$'

# 1) gh pr merge — GitHub 상의 base 브랜치를 직접 변경하므로 계속 가드
# 2) git merge/rebase — "무엇을 가져오느냐"가 아니라 "현재 브랜치가 보호 브랜치냐"를 기준으로 가드
#    feature 브랜치에서 git merge origin/main / git rebase origin/main 으로 default branch 를 싱크하는 작업은 허용한다.
if [[ "$command" =~ (^|[[:space:]\;])gh[[:space:]]+pr[[:space:]]+merge([[:space:]]|$) ]]; then
  match="gh pr merge"
else
  git_dir="."
  git_op=""

  if [[ "$command" =~ (^|[[:space:]\;])git[[:space:]]+-[Cc][[:space:]]+([^[:space:]\;]+)[[:space:]]+(merge|rebase)([[:space:]]|$) ]]; then
    git_dir="${BASH_REMATCH[2]}"
    git_op="${BASH_REMATCH[3]}"
  elif [[ "$command" =~ (^|[[:space:]\;])git[[:space:]]+(merge|rebase)([[:space:]]|$) ]]; then
    git_op="${BASH_REMATCH[2]}"
  else
    exit 0
  fi

  if [[ "$git_op" == "merge" && "$command" =~ (^|[[:space:]\;])git([[:space:]]+-[Cc][[:space:]]+[^[:space:]\;]+)?[[:space:]]+merge[[:space:]]+--(abort|quit)([[:space:]]|$) ]]; then
    exit 0
  fi
  if [[ "$git_op" == "rebase" && "$command" =~ (^|[[:space:]\;])git([[:space:]]+-[Cc][[:space:]]+[^[:space:]\;]+)?[[:space:]]+rebase[[:space:]]+--(abort|quit)([[:space:]]|$) ]]; then
    exit 0
  fi

  current_branch=$(git -C "$git_dir" branch --show-current 2>/dev/null || true)
  if [[ ! "$current_branch" =~ $protected_branch_re ]]; then
    exit 0
  fi

  match="git $git_op on protected branch ${current_branch}"
fi

cat >&2 <<EOF
🛑 보호 브랜치에서의 머지/리베이스는 실행할 수 없습니다.

감지된 명령: $match

정책:
  - main/stage-* 로 직접 변경을 올리는 동작은 금지
  - 작업 브랜치에서 main/default branch 를 가져와 싱크하는 동작은 허용
    예: git merge origin/main, git rebase origin/main, git pull origin main

필요하면 다음을 사용자에게 보고하세요:
  1) 현재 브랜치: \$(git branch --show-current)
  2) base/target 브랜치 (main / stage-*)
  3) gh pr merge 인 경우: PR 번호 + 머지 방식(--squash / --merge / --rebase) + base
  4) 영향 받을 후속 워크플로 (CI 트리거 / 자동 배포)

본 레포 컨벤션:
  - PR 머지는 GitHub UI 또는 사용자가 직접 실행 (CI 보호 + 리뷰 흔적 보존)
  - main 기반 TBD 전략 사용
  - feature → feature 머지는 CI 가 안 도므로 base 가 main/stage-* 인지 반드시 확인
EOF
exit 2
