#!/usr/bin/env bash
# Codex payload → Claude 형태 payload 로 변환해 `.agent/hooks/` 의 **원본 훅을 그대로** 호출한다.
#
# 목적: 기존 훅 스크립트를 한 줄도 수정하지 않고 Codex 를 커버한다.
#   Claude Code : tool_name = Edit|Write|MultiEdit,  tool_input.file_path = "경로" (1개)
#   Codex       : tool_name = apply_patch,           tool_input.command  = 패치 텍스트 (N개 파일)
#
# apply_patch 한 건이 여러 파일을 담으므로 **파일마다 원본 훅을 1회씩** 호출한다.
#
# mode:
#   block    — 원본이 non-zero 로 끝나면 그 코드로 즉시 종료한다 (가드. 차단 이유는 stderr 전달)
#   advisory — 원본의 첫 JSON 출력만 stdout 으로 전달한다 (리마인더. JSON 이 여러 개면 무효 출력이 된다)

codex_delegate() {
  local original_name="$1" mode="$2"
  local lib_dir root original
  lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  root="$(cd "$lib_dir/../.." && pwd)"
  original="$root/.agent/hooks/$original_name"

  if [[ ! -x "$original" ]]; then
    printf '⚠ 원본 훅을 찾을 수 없습니다: %s\n' "$original" >&2
    exit 0
  fi

  # shellcheck source=hook-io.sh
  source "$lib_dir/hook-io.sh"

  local input files new_text cwd event
  input=$(cat)
  files=$(hook_io_file_paths "$input")
  [[ -z "$files" ]] && exit 0
  new_text=$(hook_io_new_text "$input")
  cwd=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('cwd',''))" 2>/dev/null || true)
  event=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('hook_event_name','PreToolUse'))" 2>/dev/null || echo PreToolUse)
  [[ -z "$cwd" ]] && cwd="$root"

  local file payload out code first=""
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue

    payload=$(FILE="$file" NEW_TEXT="$new_text" CWD="$cwd" EVENT="$event" python3 -c '
import json, os
print(json.dumps({
    "hook_event_name": os.environ["EVENT"],
    "tool_name": "Write",
    "cwd": os.environ["CWD"],
    "tool_input": {
        "file_path": os.environ["FILE"],
        "content": os.environ["NEW_TEXT"],
        "new_string": os.environ["NEW_TEXT"],
    },
}))')

    if [[ "$mode" == "block" ]]; then
      out=$(printf '%s' "$payload" | "$original" 2>&1)
      code=$?
      [[ -n "$out" ]] && printf '%s\n' "$out" >&2
      (( code != 0 )) && exit "$code"
    else
      out=$(printf '%s' "$payload" | "$original" 2>/dev/null)
      [[ -z "$first" && -n "$out" ]] && first="$out"
    fi
  done <<< "$files"

  if [[ "$mode" == "advisory" && -n "$first" ]]; then
    printf '%s\n' "$first"
  fi
  exit 0
}
