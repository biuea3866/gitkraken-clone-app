---
name: custom-pr-create
description: >
  Undine 컨벤션에 맞춰 PR 본문을 채워서 gh pr create 호출을 준비한다.
  PR template(.github/pull_request_template.md) 5섹션을 SSOT 로 그대로 따르고(별도 섹션 추가 없음),
  티켓 접두사 + base main + 셀프 코드 리뷰 게이트를 적용하고, PR 은 기본 Draft 로 생성한다.
  Use when:
  - 사용자가 "PR 만들어줘" / "/custom-pr-create" 호출
  - 브랜치명 또는 커밋 메시지에 UND-NN 이 들어있을 때 자동 추론 가능
---

# custom-pr-create

표준 PR 본문 템플릿을 채워 `gh pr create` 명령을 생성한다. 사용자 확인 후 실행.

## how to apply

### Step 0: 셀프 코드 리뷰 게이트 (필수)

PR 본문 작성·`gh pr create` 호출 **이전에 반드시** 사용자에게 묻는다:

> **"PR 생성 전 셀프 코드 리뷰(`/custom-self-code-review`)를 먼저 진행할까요?"**

- **예** → [[custom-self-code-review]] 를 먼저 수행하고, 그 결과(특히 사이드 이펙트·롤백)를 본문에 반영한다.
- **아니오/이미 함** → 진행하되, 체크리스트 항목 상태를 정확히 반영한다.

> 이 질문은 **생략 금지**.

### Step 1: 컨텍스트 수집

```bash
git log -1 --pretty=%s
git branch --show-current      # 예: feat/UND-14-graph-view
```

티켓 키가 없으면 사용자에게 묻는다 — 없는 경우 제목 형식 `[NO-TICKET] - <type>: <내용>`.

### Step 2: 변경 파일 분석

> **base 브랜치 = `main`.**

```bash
git diff --name-only origin/main..HEAD
```

→ 영향 범위 추론. 필요 시 다음 에이전트 호출:
- `custom-pr-call-graph-reviewer` — 변경 파일 밖 1-2 hop 파급
- `custom-silent-failure-hunter` — 에러 처리 변경이 포함될 때
- `custom-kotlin-desktop-engineer` — 레이어·컨벤션 점검

### Step 3: 티켓 본문 인용

```bash
cat tickets/UND-NN-*.md
```

→ "작업 내용"과 "테스트 케이스"를 PR 본문 작성 근거로 쓴다.

### Step 4: 본문 채우기

**PR 본문 포맷의 SSOT 는 [`.github/pull_request_template.md`](../../../.github/pull_request_template.md) 다.**
먼저 이 파일을 읽어 섹션의 **제목·순서·주석을 그대로 따른다**. 섹션을 본 스킬에 하드코딩 복제하지 않는다 —
템플릿이 바뀌면 자동으로 따라간다.

- **개요** — `- [UND-NN](../tickets/UND-NN-*.md) — {티켓 제목}` + 한 줄 설명
- **작업 내용** — 핵심 변경 bullet 1~3줄
- **체크리스트** — 실제 실행한 것만 체크한다. `./gradlew test` 를 돌리지 않았으면 체크하지 않는다
- **스크린샷** — UI 티켓이면 필수, 아니면 "해당 없음"
- **추가 유의사항** — 설정 스키마 변경·파괴적 Git 연산·롤백 방법

> **템플릿 섹션 외에 별도 섹션을 추가하지 않는다.**

#### Step 4.5: 제출 전 본문 자가 검증 (필수)

1. **섹션 전부 존재** — 템플릿의 `###` 헤더가 하나도 빠지지 않았는가 (내용이 없어도 헤더 + "해당 없음" 유지)
2. **템플릿 안내 주석 잔존 0** — 본문에 `<!--` 가 남아 있으면 제거
3. **제목 포맷** — `[UND-NN] - <type>: <summary>` (콜론 앞 공백 `feat :` ❌, 다중 prefix `[A][B]` ❌ —
   `custom-check-commit-prefix.sh` 훅이 `gh pr create` 단계에서도 차단)

```bash
grep -c '^### ' <<< "$BODY"      # 템플릿 섹션 수와 일치해야 함
grep -n '<!--' <<< "$BODY" || echo "주석 잔존 없음 ✅"
```

하나라도 걸리면 수정 후 재검증 — 통과 전에는 Step 5 로 넘어가지 않는다.

### Step 5: gh pr create 명령 생성

**base 는 반드시 `main`.** **PR 은 기본 `--draft`.** 사용자가 "ready 로 올려줘" 라고 명시하기 전에는 항상 Draft 다.
Draft 로 올린 뒤 준비되면 `gh pr ready <번호>` 로 전환한다.

```bash
gh pr create --draft --base main --head <head> --title "[UND-NN] - <type>: <summary>" --body "$(cat <<'BODY'
<위 본문>
BODY
)"
```

> **사용자 명시 승인 후 실행.** 본문을 보여주고 확인받는다.

## 안티패턴

- ❌ **Step 0 셀프 리뷰 게이트 질문 없이** 곧장 본문 생성 / `gh pr create`
- ❌ 기본적으로 Draft 가 아닌 일반 PR 로 생성
- ❌ 실행하지 않은 체크리스트 항목을 체크 — 거짓 완전성
- ❌ 템플릿 안내 주석(`<!-- ... -->`)을 지우지 않은 채 제출
- ❌ 섹션 헤더 자체를 삭제 — 내용 없으면 "해당 없음" 으로 유지
- ❌ 템플릿에 없는 별도 섹션을 임의 추가
- ❌ Step 4.5 자가 검증 없이 Step 5 진행

## 관련 스킬

- [[custom-self-code-review]] — 셀프 코드 리뷰(5축), PR 본문 작성 직전 권장
- [[custom-pr-review]] — 올라간 PR 을 로컬에서 다축 리뷰
- [[custom-release-tagger]] — 릴리즈 태그
