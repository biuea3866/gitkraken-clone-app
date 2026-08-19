#!/usr/bin/env bash
# PreToolUse(apply_patch) — 시크릿 파일 편집 차단. 패치에 담긴 모든 파일 검사.
# 원본 훅(.agent/hooks/custom-secrets-edit-guard.sh)은 수정하지 않는다 — payload 변환만 담당한다.
set -uo pipefail
# shellcheck source=../lib/delegate.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/delegate.sh"
codex_delegate custom-secrets-edit-guard.sh block
