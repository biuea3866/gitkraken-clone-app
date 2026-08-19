#!/usr/bin/env bash
# PostToolUse(apply_patch) — .kt/.kts 변경 시 모듈 detekt 리마인더.
# 원본 훅(.agent/hooks/custom-detekt-touch-reminder.sh)은 수정하지 않는다 — payload 변환만 담당한다.
set -uo pipefail
# shellcheck source=../lib/delegate.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/delegate.sh"
codex_delegate custom-detekt-touch-reminder.sh advisory
