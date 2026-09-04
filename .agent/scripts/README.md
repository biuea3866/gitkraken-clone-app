# .agent/scripts — harness 유지보수 스크립트

읽기 전용 검증/유지보수용 셸 스크립트. 추가 시 `chmod +x` + 본 README 한 줄 + `bash -n` 통과를 전제로 한다.

| 스크립트 | 용도 |
|---|---|
| `validate-harness.sh` | 카운트·cross-file 정합 검증 — 각 README 헤딩 카운트 ↔ 실제 파일 수(agents/skills/hooks/rules), `settings.json` hook 등록 ↔ `hooks/*.sh`, HARNESS.md/onboarding.md 가변 카운트 하드코딩 금지. 표면 추가/삭제 직후 실행해 **경고 0** 을 확인한다(HARNESS rubric F⒞·H 의 자동 점검 수단). |
| `make-bench-repo.sh` | 그래프 성능 측정용 **합성 저장소** 생성 (UND-88) — 고정 seed `git fast-import` 로 main 이력·주기적 병합·끝까지 병합하지 않은 브랜치를 결정적으로 만든다. 산출물은 테스트 산출물이라 커밋하지 않는다. `GraphHistoryBenchSpec` 이 `UNDINE_BENCH_REPO` 로 이 경로를 받는다. 토폴로지 보장과 실패 시 파일 보존은 `MakeBenchRepoScriptSpec` 이 작은 저장소로 실제 실행해 고정한다. |

## 실행

```bash
bash .agent/scripts/validate-harness.sh            # exit 0 = 정합(경고 0) / exit 1 = 불일치

# 벤치 저장소 생성 → 그래프 벤치 실행 (저장소가 없으면 벤치 스펙은 건너뛴다)
.agent/scripts/make-bench-repo.sh --commits 26000 --branches 800 --output /tmp/undine-bench
UNDINE_BENCH_REPO=/tmp/undine-bench ./gradlew :app:test --tests '*GraphHistoryBench*'
```
