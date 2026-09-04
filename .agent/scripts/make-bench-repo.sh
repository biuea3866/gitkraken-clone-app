#!/usr/bin/env bash
#
# 그래프 성능 측정용 **합성 저장소**를 결정적으로 만든다 (UND-88).
#
# 왜 필요한가 — 레인 배치와 이력 페이징이 대형 저장소에서 버티는지 재려면 수만 커밋짜리
# 저장소가 있어야 한다. 진짜 저장소를 쓰면 사람마다 다른 것을 재고, 매번 새로 만들면 같은
# 것을 두 번 재지 못한다. 고정 seed 로 `git fast-import` 스트림을 만들어 **같은 인자면 같은
# 커밋 그래프**가 나오게 한다.
#
# 만들어지는 토폴로지 — main 이력 + 동시 브랜치 + 주기적 병합 + 끝까지 병합하지 않은 브랜치.
# 두 지점은 무작위에 맡기지 않는다: **두 번째 커밋**은 브랜치에 심어 첫 병합 대상을 보장하고,
# **마지막 커밋**은 브랜치에 고정해 미병합 tip 을 보장한다. 마지막 10% 구간에서는 병합하지 않는다.
# 생성 후 병합 커밋 수와 각 브랜치 tip 의 main 조상 여부를 실제로 확인한 뒤에만 결과를 내놓는다.
#
# 산출물은 **테스트 산출물**이다. 커밋하지 않는다 — 저장소 밖(예: /tmp)에 만들 것을 권한다.
#
# 사용:
#   .agent/scripts/make-bench-repo.sh --commits 26000 --branches 800 --output /tmp/undine-bench
#   UNDINE_BENCH_REPO=/tmp/undine-bench ./gradlew :app:test --tests '*Bench*'
#
set -euo pipefail

readonly DEFAULT_SEED=20260904
# 커밋 시각의 기준점. 고정값이라 같은 인자면 같은 커밋 해시가 나온다.
readonly BASE_TIME=1767225600
readonly AUTHOR='Undine Bench <bench@undine.dev>'
# 몇 커밋마다 병합할지. 사전 거부 조건과 awk 가 같은 값을 봐야 해서 셸 쪽에 둔다.
readonly MERGE_EVERY=7

commits=""
branches=""
output=""
seed="$DEFAULT_SEED"

usage() {
  cat <<'USAGE'
사용: make-bench-repo.sh --commits <N> --branches <N> --output <PATH> [--seed <N>]

  --commits   총 커밋 수 (8 이상 — 그보다 작으면 병합 지점이 존재할 수 없다)
  --branches  동시 브랜치 수 (1 이상)
  --output    생성 위치. 없거나 비어 있어야 한다 — 기존 내용을 지우지 않는다.
  --seed      난수 seed. 기본값 20260904. 같은 seed·같은 인자면 같은 그래프가 나온다.
USAGE
}

fail() {
  echo "make-bench-repo: $*" >&2
  exit 1
}

require_positive_integer() {
  local name="$1" value="$2" minimum="$3"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$name 은 정수여야 합니다: $value"
  (( value >= minimum )) || fail "$name 은 $minimum 이상이어야 합니다: $value"
}

while (( $# > 0 )); do
  case "$1" in
    --commits) commits="${2-}"; shift 2 ;;
    --branches) branches="${2-}"; shift 2 ;;
    --output) output="${2-}"; shift 2 ;;
    --seed) seed="${2-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; fail "알 수 없는 인자: $1" ;;
  esac
done

if [[ -z "$commits" || -z "$branches" || -z "$output" ]]; then
  usage >&2
  fail "--commits · --branches · --output 이 모두 필요합니다"
fi
require_positive_integer "--commits" "$commits" 2
require_positive_integer "--branches" "$branches" 1
require_positive_integer "--seed" "$seed" 1

command -v git >/dev/null 2>&1 || fail "git 을 찾지 못했습니다"

# 끝의 `/` 를 떼어 둔다 — dirname 과 마지막 mv 가 경로를 같은 것으로 보게 한다.
while [[ "$output" != "/" && "$output" == */ ]]; do output="${output%/}"; done

# 병합은 `mark % MERGE_EVERY == 0` 이고 cutoff 이하인 mark 에서만 일어난다. cutoff 이 MERGE_EVERY
# 보다 작으면 그런 mark 자체가 없어 **어떤 seed 로도** 병합 커밋이 나올 수 없다 — 만들어 보고
# 실패하지 말고 여기서 거부한다.
merge_cutoff=$(( commits * 9 / 10 ))
if (( merge_cutoff < MERGE_EVERY )); then
  fail "--commits 가 너무 작아 병합 지점이 존재할 수 없습니다: $commits (최소 $(( MERGE_EVERY * 10 / 9 + 1 )))"
fi

if [[ -e "$output" ]]; then
  [[ -d "$output" ]] || fail "출력 경로가 디렉터리가 아닙니다: $output"
  [[ -z "$(ls -A "$output")" ]] || fail "출력 디렉터리가 비어 있지 않습니다: $output"
fi

# **사용자가 지정한 경로를 실패 정리 대상으로 삼지 않는다.** "지금 비어 있으니 지워도 된다" 는
# 사후 판단은 확인과 삭제 사이에 경로가 바뀌면(다른 프로세스가 쓰거나 심링크가 교체되면) 남의
# 파일을 지운다. 대신 우리가 mktemp 로 **만든** staging 에서 전부 만들고 검증한 뒤, 성공했을 때만
# 출력 경로로 옮긴다 — 재귀 삭제 대상은 언제나 우리 소유의 staging 하나뿐이다.
output_parent=$(dirname "$output")
mkdir -p "$output_parent"
staging=$(mktemp -d "$output_parent/.make-bench-repo.XXXXXX")

cleanup_staging() {
  local status=$?
  rm -rf "$staging"
  exit "$status"
}
trap cleanup_staging EXIT

git init --quiet --initial-branch=main "$staging"

# fast-import 스트림 생성. awk 자신의 rand() 를 쓰지 않는다 — 구현마다 결과가 달라
# "같은 seed 면 같은 그래프" 가 깨진다. MINSTD(16807) 를 직접 돌린다.
LC_ALL=C awk \
  -v commits="$commits" \
  -v branches="$branches" \
  -v seed="$seed" \
  -v baseTime="$BASE_TIME" \
  -v mergeEvery="$MERGE_EVERY" \
  -v author="$AUTHOR" '
function nextRand() {
  seed = (seed * 16807) % 2147483647
  return seed
}

function emitCommit(mark, ref, parent, mergeParent, lane,   message, content, path, stamp) {
  stamp = baseTime + mark * 60
  message = (mergeParent > 0 ? "merge branch into main " mark : "bench commit " mark) "\n"
  printf "commit %s\n", ref
  printf "mark :%d\n", mark
  printf "author %s %d +0000\n", author, stamp
  printf "committer %s %d +0000\n", author, stamp
  printf "data %d\n%s", length(message), message
  if (parent > 0) printf "from :%d\n", parent
  if (mergeParent > 0) printf "merge :%d\n", mergeParent
  path = sprintf("lane-%d/f%d.txt", lane, mark % 64)
  content = "commit " mark "\n"
  printf "M 100644 inline %s\ndata %d\n%s\n", path, length(content), content
}

# 병합할 브랜치를 결정적으로 고른다. 시작된 브랜치가 없으면 0.
function pickStartedBranch(   offset, step, slot) {
  offset = nextRand() % branches
  for (step = 0; step < branches; step++) {
    slot = ((offset + step) % branches) + 1
    if (tip[slot] != 0) return slot
  }
  return 0
}

BEGIN {
  # 마지막 10% 에서는 병합하지 않는다 — 그래야 끝까지 병합되지 않은 브랜치가 남는다.
  mergeCutoff = int(commits * 9 / 10)
  # 첫 병합 대상이 될 브랜치를 심는 지점. 첫 병합 지점보다 앞이어야 한다.
  branchSeedMark = 2

  emitCommit(1, "refs/heads/main", 0, 0, 0)
  tip[0] = 1
  for (slot = 1; slot <= branches; slot++) tip[slot] = 0

  for (mark = 2; mark <= commits; mark++) {
    # 마지막 커밋은 **반드시** 브랜치에 얹는다. 마지막 10% 를 무작위 배치에만 맡기면 그 구간이
    # 전부 main 으로 떨어질 수 있고, 그러면 "끝까지 병합하지 않은 브랜치" 가 하나도 남지 않는다.
    # 브랜치 ref 위의 커밋은 병합으로만 main 에 들어오는데 이 커밋 뒤에는 병합이 없으므로,
    # 이 tip 은 main 의 조상이 될 수 없다.
    if (mark == commits) {
      lane = (nextRand() % branches) + 1
      ref = sprintf("refs/heads/bench/branch-%d", lane)
      parent = (tip[lane] == 0) ? tip[0] : tip[lane]
      emitCommit(mark, ref, parent, 0, lane)
      tip[lane] = mark
      continue
    }
    # 첫 병합 지점(mark == mergeEvery) 이전에 **반드시** 브랜치 tip 을 하나 만든다. 어느 브랜치에
    # 얹을지만 seed 로 정하고, "브랜치에 얹는다" 자체는 무작위에 맡기지 않는다. 앞 구간 배치를
    # 전부 난수에 맡기면 작은 입력(--commits 8 --branches 1)에서 그 구간이 모두 main 으로 떨어져
    # 병합할 tip 이 없고, 그러면 아래 검증에 걸려 실패한다 — 받아들인 인자는 어떤 seed 에서도
    # 병합 커밋을 내야 한다. mark 2 는 mergeEvery 보다 앞이고 그 사이에 병합이 없으므로,
    # 이 tip 은 첫 병합 시점까지 살아 있다.
    if (mark == branchSeedMark) {
      lane = (nextRand() % branches) + 1
      emitCommit(mark, sprintf("refs/heads/bench/branch-%d", lane), tip[0], 0, lane)
      tip[lane] = mark
      continue
    }
    if (mark % mergeEvery == 0 && mark <= mergeCutoff) {
      target = pickStartedBranch()
      if (target > 0) {
        emitCommit(mark, "refs/heads/main", tip[0], tip[target], 0)
        tip[0] = mark
        tip[target] = 0
        continue
      }
    }
    lane = nextRand() % (branches + 1)
    if (lane == 0) {
      ref = "refs/heads/main"
      parent = tip[0]
    } else {
      ref = sprintf("refs/heads/bench/branch-%d", lane)
      parent = (tip[lane] == 0) ? tip[0] : tip[lane]
    }
    emitCommit(mark, ref, parent, 0, lane)
    tip[lane] = mark
  }
  printf "done\n"
}
' | git -C "$staging" fast-import --quiet --done

git -C "$staging" checkout --quiet --force main

# 열린 브랜치를 **ref 개수로 세지 않는다.** 병합해도 그 브랜치 ref 는 남아 있어서, 개수만 보면
# tip 이 이미 main 의 조상이 된 브랜치까지 "끝까지 병합하지 않은 브랜치" 로 오판한다.
# tip 이 main 의 조상인지를 ref 마다 직접 묻는다.
unmerged_branches=0
while IFS= read -r ref; do
  [[ -n "$ref" ]] || continue
  if ! git -C "$staging" merge-base --is-ancestor "$ref" main; then
    unmerged_branches=$(( unmerged_branches + 1 ))
  fi
done < <(git -C "$staging" for-each-ref --format='%(refname)' refs/heads/bench)

merge_count=$(git -C "$staging" rev-list --merges --count main)
main_count=$(git -C "$staging" rev-list --count main)

if (( unmerged_branches < 1 )); then
  fail "main 에 병합되지 않은 브랜치 tip 이 없습니다 — --commits 를 --branches 보다 충분히 크게 주세요"
fi
if (( merge_count < 1 )); then
  fail "병합 커밋이 없습니다 — --commits 를 늘리세요"
fi

# 여기까지 왔을 때만 사용자 경로를 만진다. 빈 디렉터리는 rmdir 로만 치운다 —
# 확인 이후 내용이 생겼다면 rmdir 이 실패하고, 우리는 아무것도 지우지 않은 채 멈춘다.
if [[ -d "$output" ]]; then
  rmdir "$output" || fail "출력 디렉터리가 비어 있지 않습니다: $output"
fi
mv "$staging" "$output"

trap - EXIT

cat <<SUMMARY
생성 완료: $output
  커밋(main 도달 가능)  $main_count
  병합 커밋             $merge_count
  미병합 브랜치         $unmerged_branches
  seed                  $seed
SUMMARY
