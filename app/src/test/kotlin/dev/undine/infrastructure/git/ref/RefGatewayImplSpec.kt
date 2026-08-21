package dev.undine.infrastructure.git.ref

import dev.undine.domain.CommitId
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File

private const val AUTHOR_NAME = "Undine Test"
private const val AUTHOR_EMAIL = "test@undine.dev"
private const val INITIAL_BRANCH = "main"
private const val CONCURRENT_CALLERS = 8

private fun initRepository(directory: File): Git =
    Git.init().setDirectory(directory).setInitialBranch(INITIAL_BRANCH).call().also { git ->
        git.repository.config.apply {
            setString("user", null, "name", AUTHOR_NAME)
            setString("user", null, "email", AUTHOR_EMAIL)
            save()
        }
    }

private fun Git.commitFile(path: String, content: String, message: String): RevCommit {
    File(repository.workTree, path).writeText(content)
    add().addFilepattern(path).call()
    return commit().setMessage(message).call()
}

private fun Git.workTreeFile(path: String): File = File(repository.workTree, path)

/**
 * `RefGatewayImpl` 통합 테스트. JGit 을 mock 하지 않고 `tempdir()` 에 실제 저장소를 만들며,
 * 원격은 네트워크가 아니라 로컬 파일 경로를 쓴다 ([[testing]] 규칙 1·2).
 *
 * 테스트가 연 `Git` 핸들은 네이티브 파일 핸들과 mmap 버퍼를 잡으므로 테스트마다 닫는다
 * ([[jgit-usage]] 규칙 1). Gateway 는 프로덕션과 같은 경계를 쓰도록 테스트가 연 핸들 하나를
 * 그대로 감싼 [GitAccess] 로 만든다 — 두 번째 핸들을 열지 않는다.
 */
class RefGatewayImplSpec : FunSpec({

    val openedRepositories = mutableListOf<Git>()

    afterTest {
        openedRepositories.forEach(Git::close)
        openedRepositories.clear()
    }

    fun newRepository(): Git = initRepository(tempdir()).also { openedRepositories += it }

    fun originWithFeatureBranch(): Git = newRepository().also { origin ->
        origin.commitFile("a.txt", "v1", "첫 커밋")
        origin.branchCreate().setName("feature").call()
    }

    fun cloneOf(origin: Git): Git =
        Git.cloneRepository()
            .setURI(origin.repository.workTree.absolutePath)
            .setDirectory(File(tempdir(), "clone"))
            .call()
            .also { clone ->
                openedRepositories += clone
                clone.repository.config.apply {
                    setString("user", null, "name", AUTHOR_NAME)
                    setString("user", null, "email", AUTHOR_EMAIL)
                    save()
                }
            }

    suspend fun gatewayFor(git: Git): RefGatewayImpl {
        val gitAccess = GitAccess(RepositoryHolder { git.repository })
        gitAccess.open(RepositoryPath(git.repository.workTree.path)) { }
        return RefGatewayImpl(gitAccess)
    }

    test("로컬·원격 브랜치 목록이 업스트림 추적 정보와 함께 반환된다") {
        val clone = cloneOf(originWithFeatureBranch())
        val gateway = gatewayFor(clone)

        val branches = gateway.listBranches()

        branches.map { it.name.value } shouldContainExactlyInAnyOrder
            listOf("main", "origin/main", "origin/feature")

        val local = branches.first { it.name.value == "main" }
        local.isCurrent shouldBe true
        local.isRemote shouldBe false
        local.upstream shouldBe RefName("origin/main")

        val remote = branches.first { it.name.value == "origin/feature" }
        remote.isCurrent shouldBe false
        remote.isRemote shouldBe true
        remote.upstream shouldBe null
        remote.ahead shouldBe 0
        remote.behind shouldBe 0
    }

    test("업스트림보다 2 커밋 앞선 브랜치의 ahead 가 2, behind 가 0 으로 계산된다") {
        val clone = cloneOf(originWithFeatureBranch())
        clone.commitFile("b.txt", "1", "두 번째 커밋")
        clone.commitFile("c.txt", "2", "세 번째 커밋")
        val gateway = gatewayFor(clone)

        val local = gateway.listBranches().first { it.name.value == "main" }

        local.ahead shouldBe 2
        local.behind shouldBe 0
    }

    test("커밋이 0건인 저장소에서 브랜치 목록은 빈 리스트를 반환한다") {
        val gateway = gatewayFor(newRepository())

        gateway.listBranches().shouldBeEmpty()
    }

    test("message 가 null 이면 lightweight 태그를 만든다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        val created = gateway.createTag(RefName("v1.0.0"), CommitId.of(head.name), null)

        created.name shouldBe RefName("v1.0.0")
        created.target shouldBe CommitId.of(head.name)
        created.isAnnotated shouldBe false
        created.message shouldBe null
        created.tagger shouldBe null

        val listed = gateway.listTags().single()
        listed.name shouldBe RefName("v1.0.0")
        listed.isAnnotated shouldBe false
    }

    test("message 가 있으면 annotated 태그를 만든다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        val created = gateway.createTag(RefName("v2.0.0"), CommitId.of(head.name), "릴리즈 2.0.0")

        created.isAnnotated shouldBe true
        created.message?.trim() shouldBe "릴리즈 2.0.0"
        created.tagger shouldBe Person(AUTHOR_NAME, AUTHOR_EMAIL)
        created.target shouldBe CommitId.of(head.name)

        val listed = gateway.listTags().single()
        listed.isAnnotated shouldBe true
        listed.target shouldBe CommitId.of(head.name)
    }

    test("태그를 삭제하면 목록에서 사라진다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)
        gateway.createTag(RefName("v1.0.0"), CommitId.of(head.name), null)

        gateway.deleteTag(RefName("v1.0.0"))

        gateway.listTags().shouldBeEmpty()
    }

    test("존재하지 않는 태그를 삭제하면 NotFound 를 던진다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        val failure = shouldThrow<UndineException.NotFound> { gateway.deleteTag(RefName("v9.9.9")) }

        failure.kind shouldBe UndineException.NotFound.Kind.REF
    }

    test("브랜치를 생성하면 지정한 커밋을 가리킨다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        val created = gateway.createBranch(RefName("feature/login"), CommitId.of(head.name))

        created.name shouldBe RefName("feature/login")
        created.target shouldBe CommitId.of(head.name)
        created.isCurrent shouldBe false
        created.isRemote shouldBe false
    }

    test("브랜치 이름을 변경하면 새 이름으로만 조회된다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)
        gateway.createBranch(RefName("old-name"), CommitId.of(head.name))

        gateway.renameBranch(RefName("old-name"), RefName("new-name"))

        gateway.listBranches().map { it.name.value } shouldContainExactlyInAnyOrder
            listOf("main", "new-name")
    }

    test("병합된 브랜치 삭제는 DELETED 를 반환한다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)
        gateway.createBranch(RefName("merged"), CommitId.of(head.name))

        gateway.deleteBranch(RefName("merged"), force = false) shouldBe DeleteBranchResult.DELETED

        gateway.listBranches().map { it.name.value } shouldContainExactlyInAnyOrder listOf("main")
    }

    test("미병합 브랜치 삭제는 REFUSED_UNMERGED 를 반환하고 force=true 면 삭제된다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        git.checkout().setCreateBranch(true).setName("wip").call()
        git.commitFile("b.txt", "wip", "미병합 커밋")
        git.checkout().setName(INITIAL_BRANCH).call()
        val gateway = gatewayFor(git)

        gateway.deleteBranch(RefName("wip"), force = false) shouldBe DeleteBranchResult.REFUSED_UNMERGED
        gateway.deleteBranch(RefName("wip"), force = true) shouldBe DeleteBranchResult.DELETED
    }

    test("이미 존재하는 이름으로 브랜치를 만들면 StateViolation 을 던진다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        shouldThrow<UndineException.StateViolation> {
            gateway.createBranch(RefName(INITIAL_BRANCH), CommitId.of(head.name))
        }
    }

    test("현재 체크아웃된 브랜치는 force 여부와 무관하게 삭제할 수 없다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        shouldThrow<UndineException.StateViolation> {
            gateway.deleteBranch(RefName(INITIAL_BRANCH), force = true)
        }
    }

    test("존재하지 않는 브랜치를 삭제하면 NotFound 를 던진다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        shouldThrow<UndineException.NotFound> { gateway.deleteBranch(RefName("ghost"), force = false) }
    }

    test("git ref 규칙에 맞지 않는 이름은 InvalidRefName 으로 거부된다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        shouldThrow<UndineException.InvalidRefName> {
            gateway.createBranch(RefName("bad..name"), CommitId.of(head.name))
        }
        shouldThrow<UndineException.InvalidRefName> {
            gateway.createTag(RefName("v 1.0"), CommitId.of(head.name), null)
        }
        shouldThrow<UndineException.InvalidRefName> {
            gateway.checkout(RefName("also..bad"), force = false)
        }
    }

    test("워킹트리가 더티하면 체크아웃이 DirtyWorkingTree 로 거부된다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        git.branchCreate().setName("feature").call()
        git.commitFile("a.txt", "v2", "두 번째 커밋")
        git.workTreeFile("a.txt").writeText("작업 중")
        val gateway = gatewayFor(git)

        val failure = shouldThrow<UndineException.DirtyWorkingTree> {
            gateway.checkout(RefName("feature"), force = false)
        }

        failure.paths shouldBe listOf("a.txt")
        git.repository.branch shouldBe INITIAL_BRANCH
    }

    test("체크아웃이 덮어쓸 추적되지 않은 파일이 있으면 DirtyWorkingTree 로 거부된다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        git.checkout().setCreateBranch(true).setName("feature").call()
        git.commitFile("b.txt", "feature 의 것", "feature 커밋")
        git.checkout().setName(INITIAL_BRANCH).call()
        git.workTreeFile("b.txt").writeText("손으로 만든 것")
        val gateway = gatewayFor(git)

        val failure = shouldThrow<UndineException.DirtyWorkingTree> {
            gateway.checkout(RefName("feature"), force = false)
        }

        failure.paths shouldContain "b.txt"
        git.repository.branch shouldBe INITIAL_BRANCH
        git.workTreeFile("b.txt").readText() shouldBe "손으로 만든 것"
    }

    test("잃을 편집이 없는 추적되지 않은 파일은 체크아웃을 막지 않는다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        git.branchCreate().setName("feature").call()
        git.workTreeFile("메모.txt").writeText("아직 add 하지 않은 파일")
        val gateway = gatewayFor(git)

        gateway.checkout(RefName("feature"), force = false)

        git.repository.branch shouldBe "feature"
        git.workTreeFile("메모.txt").readText() shouldBe "아직 add 하지 않은 파일"
    }

    test("force=true 를 명시하면 더티 상태에서도 체크아웃된다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        git.branchCreate().setName("feature").call()
        git.commitFile("a.txt", "v2", "두 번째 커밋")
        git.workTreeFile("a.txt").writeText("작업 중")
        val gateway = gatewayFor(git)

        gateway.checkout(RefName("feature"), force = true)

        git.repository.branch shouldBe "feature"
        git.workTreeFile("a.txt").readText() shouldBe "v1"
    }

    test("원격 브랜치를 체크아웃하면 로컬 추적 브랜치가 생성되고 detached HEAD 가 되지 않는다") {
        val clone = cloneOf(originWithFeatureBranch())
        val gateway = gatewayFor(clone)

        gateway.checkout(RefName("origin/feature"), force = false)

        clone.repository.fullBranch shouldBe "refs/heads/feature"
        val local = gateway.listBranches().first { it.name.value == "feature" && !it.isRemote }
        local.isCurrent shouldBe true
        local.upstream shouldBe RefName("origin/feature")
    }

    test("존재하지 않는 참조를 체크아웃하면 NotFound 를 던진다") {
        val git = newRepository()
        git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)

        val failure = shouldThrow<UndineException.NotFound> {
            gateway.checkout(RefName("ghost"), force = false)
        }

        failure.kind shouldBe UndineException.NotFound.Kind.REF
    }

    test("동시 호출은 GitAccess 가 직렬화하므로 모든 브랜치 생성이 반영된다") {
        val git = newRepository()
        val head = git.commitFile("a.txt", "v1", "첫 커밋")
        val gateway = gatewayFor(git)
        val names = (1..CONCURRENT_CALLERS).map { "feature/$it" }

        coroutineScope {
            names.forEach { name ->
                launch(Dispatchers.Default) {
                    gateway.createBranch(RefName(name), CommitId.of(head.name))
                }
            }
        }

        gateway.listBranches().map { it.name.value } shouldContainExactlyInAnyOrder
            (names + INITIAL_BRANCH)
    }
})
