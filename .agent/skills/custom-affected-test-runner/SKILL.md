---
name: custom-affected-test-runner
description: >
  push 전에 변경 범위에 대응하는 테스트를 실제로 실행하는 게이트.
  "테스트 통과" 단언을 실행 출력으로 뒷받침하는 것이 목적이며, 대응 테스트가 아예 없으면
  그 사실을 누락으로 보고한다.
  Use when:
  - `git push` 직전 — 변경에 대응하는 테스트가 실제로 도는지 확인할 때
  - "이 변경 테스트 돌았어?" 질문
  - 사용자가 "/custom-affected-test-runner" 호출
---

# custom-affected-test-runner

변경 범위 ↔ 테스트 대응을 확인하고 **실제로 실행**한다.
"테스트 통과" 는 실행 출력이 있을 때만 유효하다 — [[custom-self-code-review]] Axis 2 와 같은 기준이다.

## when to use

- `git push` 직전 (push 훅 안내로 호출)
- PR 생성 전 회귀 검증 경로 확인
- 신규 로직을 추가한 모든 작업

## how to apply

### Step 1: 변경 파일 산출

```bash
git diff --name-only origin/<base>..HEAD
```

base 불명확 시 `main` 가정. 문서·하네스 파일만 변경이면 테스트 대상 없음 → 종료.

### Step 2: 대응 테스트 탐색

변경된 `src/main/**/Foo.kt` 마다 대응 테스트를 찾는다.

```bash
# 클래스명으로 테스트 짝 탐색
git diff --name-only origin/main..HEAD | grep 'src/main/.*\.kt$' | while read -r f; do
  cls=$(basename "$f" .kt)
  echo "== $cls"
  grep -rl "$cls" app/src/test --include='*.kt' || echo "  (대응 테스트 없음)"
done
```

**대응 테스트가 없으면 그 자체가 finding 이다** (p2) — 실행할 게 없다고 통과시키지 않는다.

### Step 3: 실행

```bash
./gradlew test                          # 전체 (기본)
./gradlew test --tests '*GraphLane*'    # 변경 범위가 좁을 때 선별
```

- **JDK 는 `gradle.properties` 의 `undine.jvm` 이 SSOT** — `custom-gradlew-jvm-guard.sh` 훅이 미스매치를 차단한다.
- 실패하면 **출력 원문을 그대로 보고**한다. 요약해서 "일부 실패" 로 뭉개지 않는다.

### Step 4: 보고

```markdown
## 테스트 게이트 — <branch>

| 변경 파일 | 대응 테스트 | 결과 |
|---|---|---|
| `.../GraphLaneAssigner.kt` | `GraphLaneAssignerSpec.kt` | ✅ |
| `.../RemoteGatewayImpl.kt` | (없음) | ⚠ 테스트 누락 (p2) |

실행: `./gradlew test`
<실행 출력 발췌>

판정: 통과 | 실패 | 테스트 누락
```

## 안티패턴

- ❌ 실행 없이 "테스트 있으니 통과" — 존재와 통과는 다르다
- ❌ 실패 출력을 요약만 하고 원문 생략
- ❌ 대응 테스트가 없는데 "실행할 것 없음" 으로 통과 처리
- ❌ JGit 을 Mock 으로 대체한 테스트를 커버로 인정 ([`testing`](../../rules/testing.md) 규칙 1)

## 관련

- [[custom-self-code-review]] — Axis 2 가 본 skill 의 결과를 인용한다
- [[custom-pr-create]] — 체크리스트의 "테스트 통과" 근거
