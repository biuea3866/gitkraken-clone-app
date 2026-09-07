#!/usr/bin/env bash
#
# 릴리즈 자산의 **계약**을 지키는 도구 (UND-64).
#
# 왜 워크플로 YAML 이 아니라 여기인가 — YAML 안의 분기는 실행해 보지 않으면 검증할 수 없고,
# 릴리즈 워크플로는 태그를 밀어야만 돈다. 태그↔버전 대조 · 자산명 산출 · 체크섬 산출/검증처럼
# **틀리면 업데이트가 조용히 실패하는** 판단을 전부 이 스크립트로 옮겨 Kotest 가 실제로 돌려
# 본다 (app/src/test/kotlin/dev/undine/packaging/ReleaseAssetsScriptSpec.kt). YAML 에는 이 스크립트를
# 부르는 배선만 남는다.
#
# 계약은 packaging/RELEASE-CONTRACT.md 가 정본이다 — UND-48(자동 업데이트)이 코드로 읽는다.
#
# 사용:
#   packaging/release-assets.sh check-tag v1.0.0
#   packaging/release-assets.sh asset-name macos
#   packaging/release-assets.sh stage macos app/build/compose/binaries/main staged
#   packaging/release-assets.sh checksums staged
#   packaging/release-assets.sh verify-checksums staged
#
set -euo pipefail

# 자산명 접두사. 계약값이라 러너 이름·Gradle packageName 과 무관하게 고정한다.
readonly ASSET_PREFIX=undine
readonly CHECKSUMS_FILE=checksums.txt
# 계약 OS 토큰과 확장자. 러너 이름(macos-latest 등)이 아니라 이 세 값으로 고정한다.
readonly OS_TOKENS=(macos windows linux)
readonly OS_EXTENSIONS=(dmg msi deb)
# `<64자리 hex><공백 2개><파일명>` — shasum/sha256sum 의 텍스트 모드 출력 그대로다.
readonly CHECKSUM_LINE='^[0-9a-f]{64}  [^ ].*$'

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
properties_file="$script_directory/../gradle.properties"

usage() {
  cat <<'USAGE'
사용: release-assets.sh [--properties <PATH>] <명령> [인자...]

  --properties <PATH>   버전 SSOT 파일. 기본값은 레포 루트의 gradle.properties.

명령:
  version                             undine.version 을 출력한다
  check-tag <TAG>                     TAG(vX.Y.Z, MAJOR ≥ 1) 가 undine.version 과 같은지 본다. 아니면 비영 종료.
  asset-name <OS>                     계약 자산명을 출력한다 (OS: macos | windows | linux)
  stage <OS> <SOURCE_DIR> <DEST_DIR>  SOURCE_DIR 의 해당 포맷 산출물을 계약명으로 DEST_DIR 에 둔다
  checksums <DIR>                     DIR 의 세 계약 자산으로 checksums.txt 를 만든다
  verify-checksums <DIR>              DIR 의 checksums.txt 를 실제 파일과 대조한다
USAGE
}

fail() {
  echo "release-assets: $*" >&2
  exit 1
}

extension_for() {
  local os="$1" index
  for index in "${!OS_TOKENS[@]}"; do
    if [[ "${OS_TOKENS[$index]}" == "$os" ]]; then
      echo "${OS_EXTENSIONS[$index]}"
      return 0
    fi
  done
  fail "알 수 없는 OS 토큰: $os (허용: ${OS_TOKENS[*]})"
}

read_version() {
  [[ -f "$properties_file" ]] || fail "버전 파일을 찾지 못했습니다: $properties_file"
  local version
  version=$(sed -n 's/^undine\.version=//p' "$properties_file" | head -1)
  [[ -n "$version" ]] || fail "undine.version 이 없습니다: $properties_file"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "undine.version 형식이 X.Y.Z 가 아닙니다: $version"
  echo "$version"
}

asset_name_for() {
  # 명령 치환 안의 `exit` 는 서브셸만 끝낸다. 따로 받아 두지 않으면 알 수 없는 OS 토큰이
  # 확장자 없는 이름으로 조용히 통과한다 — 그 이름은 클라이언트가 영원히 찾지 못한다.
  local os="$1" extension version
  extension=$(extension_for "$os") || exit 1
  version=$(read_version) || exit 1
  echo "$ASSET_PREFIX-$version-$os.$extension"
}

# 체크섬 도구는 플랫폼마다 이름이 다르다. 어느 쪽이든 텍스트 모드 출력은 계약 형식과 같다.
checksum_command() {
  if command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256"
  elif command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum"
  else
    fail "shasum 도 sha256sum 도 찾지 못했습니다"
  fi
}

command_check_tag() {
  local tag="${1-}"
  [[ -n "$tag" ]] || fail "check-tag 에 태그가 필요합니다"
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "태그 형식이 vX.Y.Z 가 아닙니다: $tag"
  local version tagged="${tag#v}"
  # macOS jpackage 가 major 0 을 거부한다 (packaging/README.md). v0.x.y 를 통과시키면 태그 검증은
  # 지나가고 **macOS 잡에서** 실패해 릴리즈가 안 만들어진다 — 3종 빌드를 다 돌린 뒤 실패하는 것보다
  # 여기서 막는 편이 싸고, 사용자가 이유를 안다.
  [[ "${tagged%%.*}" != 0 ]] ||
    fail "MAJOR 가 0 인 태그로는 발행할 수 없습니다: $tag — macOS jpackage 가 major 0 을 거부해 dmg 를 만들지 못합니다 (packaging/README.md)"
  version=$(read_version) || exit 1
  # 어긋난 채 올리면 업데이트가 **잘못된 버전을 설치한다**. 여기서 멈추는 것이 계약이다.
  [[ "$tagged" == "$version" ]] || fail "태그와 undine.version 이 다릅니다: $tag vs $version"
  echo "$version"
}

command_stage() {
  local os="${1-}" source_directory="${2-}" destination="${3-}"
  [[ -n "$os" && -n "$source_directory" && -n "$destination" ]] ||
    fail "stage 에는 <OS> <SOURCE_DIR> <DEST_DIR> 가 모두 필요합니다"
  local extension
  extension=$(extension_for "$os") || exit 1
  [[ -d "$source_directory" ]] || fail "산출물 경로가 없습니다: $source_directory"

  # Gradle 산출물 이름은 OS 마다 다르다(Undine-1.0.0.dmg · undine_1.0.0-1_amd64.deb). 이름을
  # 흉내 내지 않고 확장자로 찾은 뒤 계약명으로 옮긴다 — 산출물 이름이 바뀌어도 계약은 안 흔들린다.
  local found=()
  while IFS= read -r line; do found+=("$line"); done < <(
    find "$source_directory" -type f -name "*.$extension" | sort
  )
  (( ${#found[@]} > 0 )) || fail "$source_directory 에서 *.$extension 산출물을 찾지 못했습니다"
  (( ${#found[@]} == 1 )) ||
    fail "*.$extension 산출물이 ${#found[@]} 개입니다 — 무엇을 발행할지 정할 수 없습니다: ${found[*]}"

  mkdir -p "$destination"
  local name target
  name=$(asset_name_for "$os") || exit 1
  target="$destination/$name"
  cp "${found[0]}" "$target"
  echo "$target"
}

command_checksums() {
  local directory="${1-}"
  [[ -n "$directory" ]] || fail "checksums 에 디렉터리가 필요합니다"
  [[ -d "$directory" ]] || fail "자산 경로가 없습니다: $directory"

  # 세 자산이 다 모이기 전에는 체크섬을 만들지 않는다. 반쪽 목록으로 발행하면 클라이언트가
  # "내 OS 것만 없다" 로 조용히 실패한다 — 부분 발행을 여기서 먼저 막는다.
  local os name names=()
  for os in "${OS_TOKENS[@]}"; do
    name=$(asset_name_for "$os") || exit 1
    [[ -f "$directory/$name" ]] || fail "발행 자산이 없습니다: $name"
    names+=("$name")
  done

  local tool output
  tool=$(checksum_command) || exit 1
  output=$(cd "$directory" && $tool "${names[@]}")

  # 도구가 계약과 다른 형식(바이너리 모드의 ` *name` 등)을 내면 UND-48 의 파싱이 깨진다.
  local line
  while IFS= read -r line; do
    [[ "$line" =~ $CHECKSUM_LINE ]] || fail "체크섬 줄이 계약 형식이 아닙니다: $line"
  done <<< "$output"

  printf '%s\n' "$output" > "$directory/$CHECKSUMS_FILE"
  echo "$directory/$CHECKSUMS_FILE"
}

command_verify_checksums() {
  local directory="${1-}"
  [[ -n "$directory" ]] || fail "verify-checksums 에 디렉터리가 필요합니다"
  [[ -f "$directory/$CHECKSUMS_FILE" ]] || fail "$CHECKSUMS_FILE 가 없습니다: $directory"
  local tool
  tool=$(checksum_command) || exit 1
  (cd "$directory" && $tool -c "$CHECKSUMS_FILE") ||
    fail "체크섬이 실제 파일과 다릅니다: $directory/$CHECKSUMS_FILE"
}

while (( $# > 0 )) && [[ "$1" == --* ]]; do
  case "$1" in
    --properties) properties_file="${2-}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) usage >&2; fail "알 수 없는 옵션: $1" ;;
  esac
done

(( $# > 0 )) || { usage >&2; fail "명령이 필요합니다"; }
command_name="$1"; shift

case "$command_name" in
  version) read_version ;;
  check-tag) command_check_tag "$@" ;;
  asset-name) [[ -n "${1-}" ]] || fail "asset-name 에 OS 토큰이 필요합니다"; asset_name_for "$1" ;;
  stage) command_stage "$@" ;;
  checksums) command_checksums "$@" ;;
  verify-checksums) command_verify_checksums "$@" ;;
  -h|--help) usage ;;
  *) usage >&2; fail "알 수 없는 명령: $command_name" ;;
esac
