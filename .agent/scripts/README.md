# .agent/scripts — harness 유지보수 스크립트

읽기 전용 검증/유지보수용 셸 스크립트. 추가 시 `chmod +x` + 본 README 한 줄 + `bash -n` 통과를 전제로 한다.

| 스크립트 | 용도 |
|---|---|
| `validate-harness.sh` | 카운트·cross-file 정합 검증 — 각 README 헤딩 카운트 ↔ 실제 파일 수(agents/skills/hooks/rules), `settings.json` hook 등록 ↔ `hooks/*.sh`, HARNESS.md/onboarding.md 가변 카운트 하드코딩 금지. 표면 추가/삭제 직후 실행해 **경고 0** 을 확인한다(HARNESS rubric F⒞·H 의 자동 점검 수단). |

## 실행

```bash
bash .agent/scripts/validate-harness.sh            # exit 0 = 정합(경고 0) / exit 1 = 불일치
```
