#!/usr/bin/env bash
# 훅 payload 정규화 shim — Claude Code 와 Codex 의 tool_input 차이를 흡수한다.
#
# 두 하네스의 훅 와이어 포맷은 동일하다 (hook_event_name / tool_name / tool_input,
# hookSpecificOutput.permissionDecision, exit 2 + stderr 차단). 단 파일 편집 표현이 다르다:
#
#   Claude Code : tool_name = Edit|Write|MultiEdit,  tool_input.file_path = "경로"
#   Codex       : tool_name = apply_patch,           tool_input.command  = "*** Begin Patch ..."
#
# Codex 는 Read 도구가 없어 파일 읽기도 tool_name = Bash 로 온다 (읽기 차단은
# custom-block-env-read.sh 가 커버). 따라서 본 shim 은 "편집 대상 파일" 추출만 담당한다.
#
# 사용법:
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/hook-io.sh"
#   files=$(hook_io_file_paths "$input")     # 개행 구분, 중복 제거. 없으면 빈 문자열
#   new_text=$(hook_io_new_text "$input")    # 새로 쓰이는 내용 (apply_patch 는 추가 라인만)

_HOOK_IO_FILE_PATHS_PY='
import json, re, sys

try:
    data = json.loads(sys.stdin.read() or "{}")
except Exception:
    sys.exit(0)

tool_input = data.get("tool_input") or {}
paths = []

direct = tool_input.get("file_path") or tool_input.get("path")
if direct:
    paths.append(direct)

# Codex apply_patch — 패치 헤더에서 대상 파일을 뽑는다. 한 패치가 여러 파일을 담을 수 있다.
command = tool_input.get("command") or ""
if "*** " in command:
    for match in re.finditer(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", command, re.M):
        paths.append(match.group(1).strip())
    for match in re.finditer(r"^\*\*\* Move to: (.+)$", command, re.M):
        paths.append(match.group(1).strip())

print("\n".join(dict.fromkeys(path for path in paths if path)))
'

_HOOK_IO_NEW_TEXT_PY='
import json, sys

try:
    data = json.loads(sys.stdin.read() or "{}")
except Exception:
    sys.exit(0)

tool_input = data.get("tool_input") or {}
text = tool_input.get("new_string") or tool_input.get("content") or ""

if not text:
    command = tool_input.get("command") or ""
    if "*** " in command:
        # apply_patch 는 추가 라인(+)만 신규 내용으로 본다. 헤더(+++)는 제외.
        added = [
            line[1:]
            for line in command.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        ]
        text = "\n".join(added)

print(text)
'

hook_io_file_paths() {
  printf '%s' "${1:-}" | python3 -c "$_HOOK_IO_FILE_PATHS_PY" 2>/dev/null || true
}

hook_io_new_text() {
  printf '%s' "${1:-}" | python3 -c "$_HOOK_IO_NEW_TEXT_PY" 2>/dev/null || true
}
