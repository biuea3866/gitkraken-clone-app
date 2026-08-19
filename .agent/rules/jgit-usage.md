---
name: jgit-usage
description: JGit 자원 수명·스레드·성능 규칙
paths:
  - "**/infrastructure/**/*.kt"
---

# JGit 사용 규칙

JGit 은 **네이티브 파일 핸들과 mmap 버퍼를 잡는다.** 닫지 않으면 장시간 실행되는 데스크톱 앱에서 핸들이 고갈된다.

## 자원 수명

1. **`use {}` 강제.** `Repository`·`RevWalk`·`TreeWalk`·`ObjectReader`·`DiffFormatter`·`Git` 은 전부 `AutoCloseable` 이다.
   `use {}` 없이 여는 코드는 p1 이다.
2. **`Repository` 는 앱 수명 동안 1개만.** 열 때마다 새로 만들면 객체 캐시가 무효화돼 그래프 로딩이 느려진다.
   저장소 전환 시에만 이전 것을 닫고 새로 연다.
3. **`RevWalk` 는 재사용하지 않는다.** 한 번 순회하면 상태가 남는다. 조회 단위로 새로 만들고 닫는다.

```kotlin
// ✅ 조회 단위로 열고 닫는다
fun loadCommits(limit: Int): List<Commit> =
    RevWalk(repository).use { walk ->
        walk.markStart(walk.parseCommit(repository.resolve(Constants.HEAD)))
        walk.take(limit).map { it.toCommit() }
    }
```

## 스레드

4. **Git I/O 는 절대 UI 스레드에서 실행하지 않는다.** 전부 `Dispatchers.IO` 로 넘긴다.
   대형 저장소의 `RevWalk` 는 초 단위로 걸려 Compose 프레임을 통째로 떨어뜨린다.
5. **`Repository` 인스턴스는 스레드 안전하지 않다.** 동시 접근은 단일 디스패처(`limitedParallelism(1)`)나
   뮤텍스로 직렬화한다.

## 성능

6. **커밋 이력은 페이징한다.** 수만 커밋 저장소에서 전체를 메모리에 올리지 않는다.
7. **diff 는 필요할 때만 계산한다.** 커밋 목록 렌더링 시점에 전체 diff 를 미리 만들지 않는다.
8. **`ObjectReader` 를 공유한다.** 한 조회 안에서 여러 객체를 읽을 때 매번 새로 열지 않는다.

## 파괴적 연산

9. **`reset --hard`·`clean -fd`·force push 는 되돌릴 수 없다.** UseCase 이름에 의도를 드러내고
   (`HardResetUseCase`), UI 는 확인 절차를 반드시 거친다.
10. **사용자 자격증명은 로그에 남기지 않는다.** [[credential-handling]] 참조.
