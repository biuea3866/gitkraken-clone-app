package dev.undine.application.search

import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.search.CommitSearchCriteria
import dev.undine.infrastructure.git.diff.DiffGatewayImpl
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val MAIN_BRANCH = "main"
private const val FEATURE_BRANCH = "feature"
private val MAIN_REF = RefName("refs/heads/$MAIN_BRANCH")
private val UTC: ZoneId = ZoneId.of("UTC")

/** 페이지 경계를 실제로 넘게 하는 작은 페이지 크기. */
private const val PAGE_SIZE = 2

private fun initRepository(directory: File): Git =
    Git.init().setDirectory(directory).setInitialBranch(MAIN_BRANCH).call()

/** 커밋 시각·작성자를 고정한다 — 실행 시각에 따라 기간 필터 결과가 흔들리면 안 된다. */
private fun Git.commitFile(path: String, message: String, at: Instant): String {
    val target = File(repository.workTree, path)
    target.parentFile?.mkdirs()
    target.writeText("content of $path at $at")
    add().addFilepattern(path).call()
    val identity = PersonIdent("Undine Tester", "tester@undine.dev", at, ZoneOffset.UTC)
    return commit()
        .setMessage(message)
        .setAuthor(identity)
        .setCommitter(identity)
        .call()
        .name
}

/** 실제 저장소를 열어 검색하고 핸들을 닫는다. */
private suspend fun searchIn(
    directory: File,
    criteria: CommitSearchCriteria,
    refs: List<RefName> = listOf(MAIN_REF),
): List<Commit> {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(directory.absolutePath)) { }
    return try {
        SearchCommitsUseCase(
            historyGateway = HistoryGatewayImpl(gitAccess),
            diffGateway = DiffGatewayImpl(gitAccess),
            pageSize = PAGE_SIZE,
        ).execute(refs, criteria)
            .filterIsInstance<SearchProgress.Match>()
            .map { match -> match.commit }
            .toList()
    } finally {
        gitAccess.close()
    }
}

/**
 * 실제 임시 저장소로 검색을 검증한다 — 경로 필터와 기간 경계는 Gateway 계약 위에서만 증명된다.
 * UND-20 은 infrastructure 를 수정하지 않고 기존 구현을 그대로 소비한다.
 */
class SearchCommitsRepositorySpec : FunSpec({

    test("파일 경로 필터가 해당 경로를 건드린 커밋만 반환한다") {
        val directory = tempdir()
        val (first, third) = initRepository(directory).use { git ->
            val added = git.commitFile("src/main/App.kt", "앱 진입점 추가", Instant.parse("2026-03-10T01:00:00Z"))
            git.commitFile("docs/readme.md", "문서 추가", Instant.parse("2026-03-10T02:00:00Z"))
            val modified = git.commitFile("src/main/App.kt", "앱 진입점 수정", Instant.parse("2026-03-10T03:00:00Z"))
            added to modified
        }

        val found = searchIn(directory, CommitSearchCriteria(filePath = "src/main"))

        found.map { it.id.value } shouldContainExactly listOf(third, first)
    }

    test("경로 필터는 여러 페이지에 걸쳐도 모든 매칭 커밋을 찾는다") {
        val directory = tempdir()
        val expected = initRepository(directory).use { git ->
            (0 until 5).map { index ->
                val path = if (index % 2 == 0) "src/main/File$index.kt" else "docs/File$index.md"
                git.commitFile(path, "커밋 $index", Instant.parse("2026-03-10T0$index:00:00Z")) to path
            }
        }
        val expectedIds = expected.filter { (_, path) -> path.startsWith("src/main") }
            .map { (id, _) -> id }
            .reversed()

        val found = searchIn(directory, CommitSearchCriteria(filePath = "src/main"))

        found.map { it.id.value } shouldContainExactly expectedIds
    }

    test("병합 커밋의 경로 판정은 첫 부모 기준이다") {
        val directory = tempdir()
        val (featureCommit, mergeCommit, mainlineCommit) = initRepository(directory).use { git ->
            git.commitFile("base.txt", "분기 이전", Instant.parse("2026-03-10T01:00:00Z"))
            git.checkout().setCreateBranch(true).setName(FEATURE_BRANCH).call()
            val onFeature = git.commitFile("feature/Only.kt", "기능 파일", Instant.parse("2026-03-10T02:00:00Z"))
            git.checkout().setName(MAIN_BRANCH).call()
            val onMainline = git.commitFile("mainline/Only.kt", "메인 파일", Instant.parse("2026-03-10T03:00:00Z"))
            val merged = git.merge()
                .include(git.repository.resolve(FEATURE_BRANCH))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setMessage("기능 브랜치 병합")
                .call()
                .newHead
                .name
            Triple(onFeature, merged, onMainline)
        }

        // 첫 부모(메인 쪽) 대비 병합 커밋의 변경은 기능 브랜치가 들여온 파일이다.
        searchIn(directory, CommitSearchCriteria(filePath = "feature/"))
            .map { it.id.value } shouldContainExactlyInAnyOrder listOf(mergeCommit, featureCommit)

        // 두 번째 부모(기능 쪽) 기준이라면 병합 커밋도 걸린다 — 첫 부모 기준이므로 걸리지 않는다.
        searchIn(directory, CommitSearchCriteria(filePath = "mainline/"))
            .map { it.id.value } shouldContainExactlyInAnyOrder listOf(mainlineCommit)
    }

    test("기간 필터의 경계값(시작일·종료일 당일 커밋)이 포함된다") {
        val directory = tempdir()
        val (onStart, onEnd) = initRepository(directory).use { git ->
            git.commitFile("before.txt", "경계 이전", Instant.parse("2026-03-09T23:59:59Z"))
            val start = git.commitFile("start.txt", "시작일", Instant.parse("2026-03-10T00:00:00Z"))
            val end = git.commitFile("end.txt", "종료일", Instant.parse("2026-03-11T23:59:59Z"))
            git.commitFile("after.txt", "경계 이후", Instant.parse("2026-03-12T00:00:00Z"))
            start to end
        }

        val found = searchIn(
            directory,
            CommitSearchCriteria(
                since = LocalDate.of(2026, 3, 10),
                until = LocalDate.of(2026, 3, 11),
                zone = UTC,
            ),
        )

        found.map { it.id.value } shouldContainExactly listOf(onEnd, onStart)
    }

    test("커밋이 없는 저장소에서 검색해도 예외 없이 0건으로 끝난다") {
        val directory = tempdir()
        initRepository(directory).use { }

        // 커밋이 없으면 HEAD 가 가리키는 커밋도 없다 — 배선은 참조 없이 검색을 건다.
        searchIn(directory, CommitSearchCriteria(message = "login"), refs = emptyList()).shouldBeEmpty()
    }

    test("메시지 검색이 대소문자를 무시하고 실제 커밋을 찾는다") {
        val directory = tempdir()
        val target = initRepository(directory).use { git ->
            val matched = git.commitFile("a.txt", "Fix LOGIN timeout", Instant.parse("2026-03-10T01:00:00Z"))
            git.commitFile("b.txt", "무관한 커밋", Instant.parse("2026-03-10T02:00:00Z"))
            matched
        }

        val found = searchIn(directory, CommitSearchCriteria(message = "login"))

        found.map { it.id.value } shouldContainExactly listOf(target)
    }
})
