---
name: custom-release-tagger
description: >
  Undine 데스크톱 앱의 릴리즈 태그(`v<major>.<minor>.<patch>`)를 만들어 push 한다.
  직전 태그 이후 커밋을 모아 릴리즈 노트 초안을 만들고, semver 증가 폭을 변경 성격으로 판정하며,
  태그는 사용자 승인 후에만 push 한다.
  Use when:
  - 사용자가 "릴리즈 태그 달아줘", "배포 태그 만들어줘" 요청
  - 사용자가 "/custom-release-tagger" 호출
---

# custom-release-tagger

릴리즈 태그를 만들고 노트 초안을 낸다. **태그 push 는 되돌리기 어려우므로 반드시 승인을 받는다.**

## how to apply

### Step 1: 현재 상태 확인

```bash
git fetch --tags
git describe --tags --abbrev=0 2>/dev/null || echo "(태그 없음 — v0.1.0 시작)"
git log $(git describe --tags --abbrev=0 2>/dev/null || echo HEAD)..HEAD --oneline
git status --short          # 워킹트리가 깨끗해야 한다
```

- 워킹트리에 변경이 남아 있으면 **중단**한다 — 태그가 가리키는 커밋과 빌드 산출물이 어긋난다.
- 현재 브랜치가 `main` 인지 확인한다.

### Step 2: semver 증가 폭 판정

| 변경 성격 | 증가 |
|---|---|
| 사용자 설정 파일 스키마 하위 호환 깨짐, 기존 동작 제거 | **major** |
| 신규 기능 추가 (화면·명령·연동) | **minor** |
| 버그 수정·성능 개선만 | **patch** |

커밋 메시지의 `feat`/`fix`/`refactor` 접두사로 1차 판정하고, 판단이 갈리면 **사용자에게 묻는다**.

### Step 3: 릴리즈 노트 초안

```markdown
## v<X.Y.Z>

### 새 기능
- (feat 커밋 요약)

### 버그 수정
- (fix 커밋 요약)

### 알려진 제약
- (있으면)
```

- 커밋 제목을 그대로 붙여넣지 않는다 — **사용자가 읽을 문장**으로 다시 쓴다.
- 티켓 키(`UND-NN`)는 노트에서 제거한다 — 사용자에게 의미가 없다.

### Step 4: 승인 후 태그 생성·push

```bash
git tag -a v<X.Y.Z> -m "v<X.Y.Z>"
git push origin v<X.Y.Z>
```

> **사용자 명시 승인 후 실행.** 태그명·대상 커밋·노트를 보여주고 확인받는다.

### Step 5: 배포 산출물 (선택)

```bash
./gradlew packageDistributionForCurrentOS
```

산출물 경로를 사용자에게 알린다. 서명·공증은 이 스킬의 범위가 아니다.

## 안티패턴

- ❌ 워킹트리가 더러운 상태에서 태그 생성
- ❌ 승인 없이 `git push origin <tag>` — 원격 태그 삭제는 이미 받은 사람에게 전파되지 않는다
- ❌ 커밋 제목을 그대로 릴리즈 노트로 사용
- ❌ semver 판정 근거 없이 patch 로 고정

## 관련

- [[custom-pr-create]] — 릴리즈 대상 변경이 머지되는 경로
