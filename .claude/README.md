# 생성된 디렉토리 — 직접 수정하지 마세요

이 디렉토리의 `agents/` · `skills/` · `rules/` 는 **`.agent/` 에서 생성된 투영본**입니다.
여기서 고친 내용은 다음 `sync-vendors.py` 실행 때 덮어써집니다.

- 편집 대상: `.agent/agents/` · `.agent/skills/` · `.agent/rules/`
- 재생성: `.agent/tools/sync-vendors.py`
- 드리프트 점검: `.agent/tools/sync-vendors.py --check`

손으로 유지하는 파일은 `settings.json` 하나뿐입니다 (Claude Code 전용 스키마 — 훅·권한).
