#!/usr/bin/env bash
#
# 준비된 자산을 GitHub Release 로 **멱등하게** 발행한다 (UND-64).
#
# 왜 워크플로 YAML 이 아니라 여기인가 — "같은 태그를 두 번 밀어도 릴리즈가 하나" 는 계약이다
# (packaging/RELEASE-CONTRACT.md). YAML 의 run 블록에 두면 태그를 실제로 밀어 보기 전에는 아무도
# 검증하지 못한다. 여기 두면 Kotest 가 `gh` 스텁으로 실제 분기를 돌려 본다
# (app/src/test/kotlin/dev/undine/packaging/PublishReleaseScriptSpec.kt).
#
# 조회 좌표(레포)를 박지 않는다 — `gh` 가 실행 컨텍스트의 저장소를 스스로 안다. 워크플로가 자기
# 레포 이름을 박아 두면 포크에서 엉뚱한 곳에 발행한다.
#
# 사용:
#   packaging/publish-release.sh v1.0.0 staged
#
set -euo pipefail

readonly CHECKSUMS_FILE=checksums.txt

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly ASSETS_SCRIPT="$script_directory/release-assets.sh"

fail() {
  echo "publish-release: $*" >&2
  exit 1
}

tag="${1-}"
assets_directory="${2-}"
if [[ -z "$tag" || -z "$assets_directory" ]]; then
  echo "사용: publish-release.sh <TAG> <ASSETS_DIR>" >&2
  exit 1
fi

command -v gh >/dev/null 2>&1 || fail "gh 를 찾지 못했습니다"

# 발행 직전에 다시 본다. 세 자산과 체크섬이 실제로 맞는지 확인하기 전에는 아무것도 올리지 않는다 —
# 반쪽 릴리즈를 만든 뒤 되돌리는 것보다 만들지 않는 편이 싸다.
bash "$ASSETS_SCRIPT" check-tag "$tag" >/dev/null
bash "$ASSETS_SCRIPT" checksums "$assets_directory" >/dev/null
bash "$ASSETS_SCRIPT" verify-checksums "$assets_directory"

upload_files=()
for token in macos windows linux; do
  upload_files+=("$assets_directory/$(bash "$ASSETS_SCRIPT" asset-name "$token")")
done
upload_files+=("$assets_directory/$CHECKSUMS_FILE")

# 이미 있는 태그에 create 를 부르면 실패하거나 두 번째 릴리즈가 생긴다. 존재를 먼저 묻고
# 갈라진다 — 재실행이 정상 시나리오다 (러너 하나가 죽어 다시 돌리는 일이 흔하다).
if ! gh release view "$tag" >/dev/null 2>&1; then
  gh release create "$tag" --title "$tag" --generate-notes "${upload_files[@]}"
  exit 0
fi

# 이미 붙어 있는 자산은 **건드리지 않는다.** `--clobber` 는 기존 자산을 지운 뒤 올리므로 그 사이
# 업로드가 실패하면 원본이 사라지고 새 자산 일부만 남는다 — 부분 발행이 재실행 경로로 들어온다.
# 릴리즈 자산 업로드는 트랜잭션이 아니라 원자성을 만들 수 없으니, 지우지 않는 쪽으로 간다.
# 지우지 않으면 "지운 뒤 실패" 경로가 아예 없다. 자산을 바꿔야 하는 상황은 사람이 릴리즈를
# 내리고 다시 태그하는 일이다.
echo "publish-release: $tag 릴리즈가 이미 있습니다 — 없는 자산만 올립니다"
existing_names=$(gh release view "$tag" --json assets --jq '.assets[].name') ||
  fail "$tag 릴리즈의 자산 목록을 읽지 못했습니다"

missing_files=()
for file in "${upload_files[@]}"; do
  name=$(basename "$file")
  if printf '%s\n' "$existing_names" | grep -qxF -- "$name"; then
    echo "publish-release: $name 은 이미 붙어 있습니다 — 건너뜁니다"
  else
    missing_files+=("$file")
  fi
done

if (( ${#missing_files[@]} == 0 )); then
  echo "publish-release: 올릴 자산이 없습니다 — $tag 릴리즈가 이미 계약대로 완전합니다"
  exit 0
fi

# 업로드가 도중에 실패하면 크게 실패한다 — 자산이 일부만 붙은 릴리즈를 사람이 보게 한다.
# 조용히 성공으로 끝내면 아무도 모른다.
gh release upload "$tag" "${missing_files[@]}"
