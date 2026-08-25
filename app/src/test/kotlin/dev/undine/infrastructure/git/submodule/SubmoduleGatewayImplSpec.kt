package dev.undine.infrastructure.git.submodule

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val MAIN = "main"
private const val PARENT_FILE = "README.md"
private const val CHILD_FILE = "lib.txt"
private const val NESTED_FILE = "nested.txt"
private const val SUBMODULE_PATH = "lib"
private const val NESTED_PATH = "inner"
private const val GIT_MODULES = ".gitmodules"
private const val SUBMODULE_SECTION = "submodule"
private const val MISSING_PATH = "없는모듈"
private const val MODULES_BRANCH_ENTRY = "branch = "

private const val CONFIG_URL_KEY = "url"
private const val CONFIG_ACTIVE_KEY = "active"

/** 실패한 추가가 기존 설정 위에 덮어쓴 값 — 되돌리기가 이 값을 남기면 남의 설정을 바꾼 것이다. */
private const val FOREIGN_URL = "https://example.invalid/덮어쓴.git"

/** 추가 이전부터 사용자가 갖고 있던 설정 — 되돌리기가 되돌려야 할 값이다. */
private const val EXISTING_URL = "https://example.invalid/기존-설정.git"

/** 실패한 추가가 인덱스에 심어 둔 gitlink — 설정 복원이 실패하면 이 값이 그대로 남아야 한다. */
private const val FOREIGN_GITLINK = "0123456789abcdef0123456789abcdef01234567"

/** 경로 이탈로 거부했음을 알리는 문구 — 미커밋·판정 불가와 사유가 섞이지 않는지 본다. */
private const val ESCAPE_REASON = "허용된 디렉터리 밖"

/** 부모 저장소 **바깥**에 있는 사용자 디렉터리와 그 안의 파일 — 어떤 경로로도 지워져선 안 된다. */
private const val OUTSIDE_DIRECTORY = "저장소-밖-사용자-디렉터리"
private const val SENTINEL_FILE = "지켜야-하는-파일.txt"
private const val SENTINEL_CONTENT = "저장소 밖 사용자 데이터\n"

/** 부모 워킹트리를 벗어나는 추가 경로 — 사용자 입력도 `.gitmodules` 와 같은 등급으로 다룬다. */
private const val ESCAPING_ADD_PATH = "../$OUTSIDE_DIRECTORY"

/** 워킹트리 안에 있으나 저장소 밖을 가리키는 심볼릭 링크 이름. */
private const val ESCAPING_LINK = "밖으로-나가는-링크"

/** 이 추가와 무관하게 원래부터 추적되던 `.gitmodules` — 되돌리기가 여기까지 망가뜨리면 안 된다. */
private const val COMMITTED_MODULES = "[submodule \"기존\"]\n\tpath = 기존\n"
private const val UNSTAGED_MODULES = "$COMMITTED_MODULES\turl = https://example.invalid/기존.git\n"

/** 커밋 시각을 고정한다 — 테스트는 현재 시각에 의존하지 않는다. */
private val FIXED_IDENT = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.ofEpochSecond(1_700_000_000L),
    ZoneOffset.UTC,
)

/**
 * 임시 디렉터리에 만든 **실제** 부모 저장소. JGit 을 Mock 으로 대체하지 않는다.
 *
 * 준비용 [Git] 과 [gateway] 가 같은 [Repository] 핸들을 공유하도록 [RepositoryHolder] 에 그 핸들을
 * 그대로 돌려준다 — `worktreeops` 스펙과 같은 방식이다.
 */
private class ParentFixture(val work: File, private val git: Git) : AutoCloseable {

    val repository: Repository = git.repository
    private val gitAccess = GitAccess(RepositoryHolder { repository })
    val gateway: SubmoduleGateway = SubmoduleGatewayImpl(gitAccess)

    /** [GitAccess] 는 열린 저장소가 있어야 동작한다 — 테스트 저장소를 한 번 연다. */
    suspend fun open() {
        gitAccess.open(RepositoryPath(work.path)) { }
    }

    fun file(relative: String): File = File(work, relative)

    fun modulesText(): String = file(GIT_MODULES).takeIf(File::exists)?.readText() ?: ""

    fun configSubsections(): Set<String> = repository.config.getSubsections(SUBMODULE_SECTION)

    fun configValue(path: String, key: String): String? = repository.config.getString(SUBMODULE_SECTION, path, key)

    /** 인덱스에 기록된 서브모듈 커밋 — 되돌리기가 gitlink 를 원래 값으로 되돌렸는지 보는 기준이다. */
    fun gitlinkId(path: String): String? = repository.readDirCache().getEntry(path)?.objectId?.name

    fun moduleGitDirectory(path: String): File = File(File(repository.commonDirectory, "modules"), path)

    fun indexPaths(): List<String> =
        repository.readDirCache().let { cache -> (0 until cache.entryCount).map { cache.getEntry(it).pathString } }

    /** 스테이징 여부까지 보려면 상태를 그대로 읽어야 한다 — 워킹트리 내용 비교만으로는 구분되지 않는다. */
    fun status(): Status = Git.wrap(repository).use { git -> git.status().call() }

    override fun close() = git.close()
}

private suspend fun openParent(directory: File): ParentFixture =
    ParentFixture(directory, Git.open(directory)).also { it.open() }

/** 다른 저장소(예: 중첩 서브모듈)의 상태는 그 저장소를 대상으로 한 게이트웨이로 읽는다. */
private suspend fun submodulesOf(directory: File): List<Submodule> {
    val access = GitAccess()
    access.open(RepositoryPath(directory.path)) { }
    return try {
        SubmoduleGatewayImpl(access).list()
    } finally {
        access.close()
    }
}

private fun Git.commitFile(work: File, name: String, content: String, message: String) {
    File(work, name).writeText(content)
    add().addFilepattern(name).call()
    commit().setMessage(message).setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
}

private fun Git.attachSubmodule(path: String, url: String) {
    submoduleAdd().setPath(path).setURI(url).call()?.use { /* 하위 저장소 핸들은 여기서 닫는다. */ }
    add().addFilepattern(GIT_MODULES).call()
    commit().setMessage("서브모듈 $path 추가").setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
}

private fun TestConfiguration.seedRepository(fileName: String): File {
    val directory = tempdir()
    Git.init().setDirectory(directory).setInitialBranch(MAIN).call().use { git ->
        git.commitFile(directory, fileName, "$fileName 최초 내용\n", "최초 커밋")
    }
    return directory
}

private fun TestConfiguration.cloneOf(source: File): File {
    val target = tempdir()
    Git.cloneRepository().setURI(source.absolutePath).setDirectory(target).call().close()
    return target
}

/** 서브모듈이 붙어 있고 이미 clone 까지 끝난 부모. */
private fun TestConfiguration.repositoryWithSubmodule(): File {
    val origin = seedRepository(CHILD_FILE)
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { it.attachSubmodule(SUBMODULE_PATH, origin.absolutePath) }
    return parent
}

/** 인덱스에는 서브모듈이 있으나 아직 clone 되지 않은 부모 — clone 직후의 정상 상태다. */
private fun TestConfiguration.repositoryWithUninitializedSubmodule(): File = cloneOf(repositoryWithSubmodule())

/** 서브모듈이 다시 서브모듈을 가진 부모의 clone — 둘 다 미초기화 상태로 시작한다. */
private fun TestConfiguration.repositoryWithNestedSubmodule(): File {
    val inner = seedRepository(NESTED_FILE)
    val middle = seedRepository(CHILD_FILE)
    Git.open(middle).use { it.attachSubmodule(NESTED_PATH, inner.absolutePath) }
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { it.attachSubmodule(SUBMODULE_PATH, middle.absolutePath) }
    return cloneOf(parent)
}

/**
 * [directory] 의 쓰기 권한을 뺀 채 [block] 을 실행한다 — 그 안의 파일을 지우는 단계만 I/O 실패로
 * 만들어 정리·되돌리기가 나머지 단계를 계속 시도하는지 확인한다. 권한은 반드시 되돌린다.
 */
private inline fun withoutWritePermission(directory: File, block: () -> Unit) {
    check(directory.setWritable(false)) { "테스트 전제: 디렉터리 쓰기 권한을 뺄 수 있어야 합니다." }
    try {
        block()
    } finally {
        directory.setWritable(true)
    }
}

/** `.gitmodules` 가 이미 추적되고 있는 부모 — 되돌리기가 호출 전 스테이징 상태를 지키는지 보는 전제다. */
private fun TestConfiguration.repositoryTrackingModules(): File {
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { git -> git.commitFile(parent, GIT_MODULES, COMMITTED_MODULES, ".gitmodules 커밋") }
    return parent
}

/** 추가가 `.gitmodules` 를 고쳐 쓰고 스테이징까지 한, 절반만 적용된 상태를 만든다. */
private fun ParentFixture.halfApplyModulesEntry() {
    file(GIT_MODULES).writeText("$UNSTAGED_MODULES[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")
    repository.stageModulesFile()
}

/** 추가 이전부터 있던 설정 섹션을 심는다 — "원래 있었다" 를 되돌리는 경로의 전제다. */
private fun ParentFixture.seedSubmoduleConfig() {
    repository.config.setString(SUBMODULE_SECTION, SUBMODULE_PATH, CONFIG_URL_KEY, EXISTING_URL)
    repository.config.save()
}

/** 추가가 워킹트리·하위 git 디렉터리·`.gitmodules` 까지 절반만 만들어 둔 상태를 만든다. */
private fun ParentFixture.halfApplyAddArtifacts() {
    File(file(SUBMODULE_PATH).also(File::mkdirs), CHILD_FILE).writeText("절반만 붙은 내용\n")
    moduleGitDirectory(SUBMODULE_PATH).mkdirs()
    file(GIT_MODULES).writeText("[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")
}

/**
 * 추가가 인덱스에 심어 둔 gitlink 엔트리를 만든다 — 되돌리기가 **인덱스까지** 호출 전 값으로
 * 되돌리는지 보는 전제다. 워킹트리·설정만 보면 gitlink 가 남거나 사라진 회귀를 놓친다.
 */
private fun ParentFixture.halfApplyGitlink() {
    repository.restoreIndexEntry(
        IndexEntrySnapshot(
            path = SUBMODULE_PATH,
            objectId = ObjectId.fromString(FOREIGN_GITLINK),
            fileMode = FileMode.GITLINK,
            length = 0,
            lastModified = Instant.EPOCH,
        ),
    )
}

/** 추가가 이미 있던 설정 섹션을 덮어쓴 상태를 만든다 — 값 교체와 키 추가를 함께 본다. */
private fun ParentFixture.overwriteSubmoduleConfig() {
    repository.config.setString(SUBMODULE_SECTION, SUBMODULE_PATH, CONFIG_URL_KEY, FOREIGN_URL)
    repository.config.setString(SUBMODULE_SECTION, SUBMODULE_PATH, CONFIG_ACTIVE_KEY, "true")
    repository.config.save()
}

/**
 * 자기만의 상위 디렉터리 안에 만든 부모 저장소와, 그 **형제 자리**에 둔 저장소 밖 사용자 파일.
 *
 * 상위 디렉터리가 테스트마다 다르므로 다른 테스트의 잔재와 섞이지 않는다 — [tempdir] 을 그대로
 * 저장소로 쓰면 형제 자리가 시스템 임시 루트가 돼 테스트끼리 같은 경로를 공유한다.
 * 경로 이탈이 막히지 않으면 재귀 삭제가 이 파일까지 온다.
 */
private fun TestConfiguration.repositoryBesideSentinel(): Pair<File, File> {
    val root = tempdir()
    val work = File(root, "부모-저장소")
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        git.commitFile(work, PARENT_FILE, "$PARENT_FILE 최초 내용\n", "최초 커밋")
    }
    val outside = File(root, OUTSIDE_DIRECTORY)
    check(outside.mkdirs()) { "테스트 전제: 저장소 밖 디렉터리를 만들 수 있어야 합니다." }
    return work to File(outside, SENTINEL_FILE).apply { writeText(SENTINEL_CONTENT) }
}

/**
 * `.git/config.lock` 자리를 디렉터리로 막아 **설정 저장만** 실패하게 만든다.
 *
 * `.git` 전체의 권한을 빼면 인덱스 쓰기까지 함께 실패해 "저장 실패 뒤에도 정리를 계속하는가" 를
 * 분리해서 볼 수 없다. 잠금 파일 자리만 막으면 실패 지점이 `config.save()` 하나로 좁혀진다.
 */
private inline fun ParentFixture.withUnwritableConfig(block: () -> Unit) {
    val lock = File(repository.directory, "config.lock")
    check(lock.mkdirs()) { "테스트 전제: config 잠금 자리를 막을 수 있어야 합니다." }
    try {
        block()
    } finally {
        deleteRecursively(lock)
    }
}

/**
 * 인덱스를 **처음 읽을 때** 한 번만 신호를 보내는 저장소.
 *
 * 대상 조회(`SubmoduleWalk.forIndex`)가 인덱스를 읽는 순간이 곧 임계구역 안이므로, 경쟁을 주입할
 * 지점을 시간이 아니라 실제 진행 상황으로 잡을 수 있다. 신호는 한 번만 보내고 그 뒤의 인덱스 읽기는
 * 그대로 통과시킨다 — 같은 연산의 실행 단계까지 붙잡으면 무엇을 재현하는지 흐려진다.
 */
private class IndexReadSignallingRepository(gitDirectory: File) : FileRepository(gitDirectory) {

    private val armed = AtomicBoolean(false)
    private var onFirstRead: () -> Unit = {}

    fun armFor(block: () -> Unit) {
        onFirstRead = block
        armed.set(true)
    }

    override fun readDirCache(): DirCache {
        if (armed.compareAndSet(true, false)) onFirstRead()
        return super.readDirCache()
    }
}

/** 전환 코루틴이 임계구역 앞에 줄을 설 시간 — 짧아도 검출력만 줄 뿐 통과 판정을 바꾸지 않는다. */
private const val SWITCH_QUEUE_MILLIS = 200L

/**
 * 저장소 **두 개**를 쥔 [GitAccess]. 조회와 실행 사이에 저장소 전환을 주입해, 한 논리 전이가
 * 하나의 임계구역 안에서 끝나는지 확인한다.
 *
 * [first] 를 연 채로 게이트웨이 연산을 시작하고, 그 연산이 대상을 조회하는 순간 [second] 로
 * 전환을 시도한다. 임계구역이 쪼개져 있으면 그 틈에 전환이 끼어 **[first] 에서 읽은 상태로
 * [second] 의 같은 경로**가 조작된다.
 */
private class SwitchRaceFixture(
    val first: File,
    val second: File,
    private val firstRepository: IndexReadSignallingRepository,
    private val secondRepository: Repository,
) : AutoCloseable {

    private val gitAccess = GitAccess(
        RepositoryHolder { key ->
            if (key == first.toPath().toRealPath()) firstRepository else secondRepository
        },
    )

    val gateway: SubmoduleGateway = SubmoduleGatewayImpl(gitAccess)

    suspend fun open() {
        gitAccess.open(RepositoryPath(first.path)) { }
    }

    /** [operation] 의 대상 조회가 임계구역에 들어간 뒤에만 전환을 시도한다 — 순서를 시간에 맡기지 않는다. */
    suspend fun raceSwitch(operation: suspend () -> Unit) {
        val lookupReached = CountDownLatch(1)
        firstRepository.armFor {
            lookupReached.countDown()
            Thread.sleep(SWITCH_QUEUE_MILLIS)
        }
        coroutineScope {
            val switching = launch(Dispatchers.IO) {
                lookupReached.await()
                gitAccess.open(RepositoryPath(second.path)) { }
            }
            operation()
            switching.join()
        }
    }

    override fun close() {
        firstRepository.close()
        secondRepository.close()
    }
}

/**
 * 두 저장소는 **같은 경로**에 서브모듈을 갖는다 — 경로가 달라 조작이 빗나가는 것이 아니라, 임계구역이
 * 붙어 있어서 조작되지 않는다는 것을 보여야 한다.
 *
 * 홀더가 저장소를 전환할 때 이전 핸들을 닫으므로 참조 수를 하나 올려 둔다. 전환 뒤에도 테스트가
 * 두 저장소를 그대로 검사할 수 있어야 한다.
 */
private fun switchRaceFixture(first: File, second: File): SwitchRaceFixture {
    val firstRepository = IndexReadSignallingRepository(File(first, Constants.DOT_GIT))
    val secondRepository = FileRepository(File(second, Constants.DOT_GIT))
    firstRepository.incrementOpen()
    secondRepository.incrementOpen()
    return SwitchRaceFixture(first, second, firstRepository, secondRepository)
}

/** 서브모듈 안에 커밋을 하나 더 쌓아 부모가 기록한 커밋과 어긋나게 만든다. */
private fun divergeSubmodule(work: File, relativePath: String, fileName: String) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        git.commitFile(submoduleWork, fileName, "서브모듈에서 진행한 작업\n", "서브모듈 커밋")
    }
}

class SubmoduleGatewayImplSpec : FunSpec({

    test("서브모듈이 없는 저장소는 빈 목록을 반환한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("서브모듈 목록이 경로·URL·상태와 함께 반환된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val submodules = fixture.gateway.list()

            submodules shouldHaveSize 1
            val submodule = submodules.single()
            submodule.path shouldBe SUBMODULE_PATH
            submodule.url.shouldNotBeNull()
            submodule.state.initialized shouldBe true
            submodule.state.locallyModified shouldBe false
            submodule.state.divergedFromRecorded shouldBe false
        }
    }

    test("미초기화 서브모듈을 초기화하면 최신 상태가 된다") {
        openParent(repositoryWithUninitializedSubmodule()).use { fixture ->
            fixture.gateway.list().single().state.initialized shouldBe false

            fixture.gateway.initialize(SUBMODULE_PATH)

            val state = fixture.gateway.list().single().state
            state.initialized shouldBe true
            state.locallyModified shouldBe false
            state.divergedFromRecorded shouldBe false
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("부모가 기록한 커밋과 실제 HEAD 가 다르면 어긋남으로 판정된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)

            val state = fixture.gateway.list().single().state
            state.initialized shouldBe true
            state.divergedFromRecorded shouldBe true
            state.locallyModified shouldBe false
        }
    }

    test("서브모듈 안에 커밋되지 않은 변경이 있으면 수정됨으로 판정된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").writeText("커밋하지 않은 변경\n")

            val state = fixture.gateway.list().single().state
            state.locallyModified shouldBe true
            state.divergedFromRecorded shouldBe false
        }
    }

    test("수정됨과 어긋남이 동시에 성립하면 둘 다 보고한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").writeText("커밋하지 않은 변경\n")

            val state = fixture.gateway.list().single().state
            state.locallyModified shouldBe true
            state.divergedFromRecorded shouldBe true
        }
    }

    test("재귀가 꺼진 초기화는 중첩 서브모듈을 건드리지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = false)

            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe false
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe false
        }
    }

    test("재귀 초기화는 중첩 서브모듈까지 초기화한다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)

            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe true
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe true
        }
    }

    test("업데이트는 부모가 기록한 커밋으로 서브모듈을 되돌린다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)

            fixture.gateway.update(SUBMODULE_PATH)

            fixture.gateway.list().single().state.divergedFromRecorded shouldBe false
        }
    }

    test("재귀가 꺼진 업데이트는 중첩 서브모듈에 적용하지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.file(SUBMODULE_PATH), NESTED_PATH, NESTED_FILE)

            fixture.gateway.update(SUBMODULE_PATH, recursive = false)
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe true

            fixture.gateway.update(SUBMODULE_PATH, recursive = true)
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe false
        }
    }

    test("recursive 를 생략한 초기화는 비재귀가 기본이라 중첩 서브모듈을 건드리지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH)

            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe false
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe false
        }
    }

    test("recursive 를 생략한 업데이트는 비재귀가 기본이라 중첩 서브모듈에 적용하지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.file(SUBMODULE_PATH), NESTED_PATH, NESTED_FILE)

            fixture.gateway.update(SUBMODULE_PATH)

            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe true
        }
    }

    test("초기화되지 않은 서브모듈 업데이트는 상태 위반으로 거부한다") {
        openParent(repositoryWithUninitializedSubmodule()).use { fixture ->
            shouldThrow<UndineException.StateViolation> { fixture.gateway.update(SUBMODULE_PATH) }

            fixture.gateway.list().single().state.initialized shouldBe false
        }
    }

    test("추가는 .gitmodules 항목·워킹트리·브랜치 설정을 남긴다") {
        val origin = seedRepository(CHILD_FILE)
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val added = fixture.gateway.add(origin.absolutePath, SUBMODULE_PATH, MAIN)

            added.path shouldBe SUBMODULE_PATH
            added.state.initialized shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
            fixture.modulesText() shouldContain "branch = $MAIN"
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.gateway.list() shouldHaveSize 1
        }
    }

    test("branch 를 생략한 추가는 .gitmodules 에 branch 항목을 만들지 않는다") {
        val origin = seedRepository(CHILD_FILE)
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val added = fixture.gateway.add(origin.absolutePath, SUBMODULE_PATH)

            added.state.initialized shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
            fixture.modulesText() shouldNotContain MODULES_BRANCH_ENTRY
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("추가가 실패하면 .gitmodules 와 생성된 디렉터리를 정리한다") {
        val missingOrigin = File(tempdir(), "없는-원격").absolutePath
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val failure = shouldThrow<UndineException> {
                fixture.gateway.add(missingOrigin, SUBMODULE_PATH)
            }

            failure.cause?.message.orEmpty() shouldNotContain missingOrigin
            fixture.file(GIT_MODULES).exists() shouldBe false
            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
            fixture.configSubsections().shouldBeEmpty()
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("워킹트리 밖을 가리키는 경로 추가는 거부하고 저장소 밖 파일을 지킨다") {
        val origin = seedRepository(CHILD_FILE)
        val (work, sentinel) = repositoryBesideSentinel()
        openParent(work).use { fixture ->
            val violation = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.add(origin.absolutePath, ESCAPING_ADD_PATH)
            }

            violation.message.orEmpty() shouldContain ESCAPE_REASON
            sentinel.readText() shouldBe SENTINEL_CONTENT
            fixture.modulesText() shouldBe ""
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("심볼릭 링크로 워킹트리 밖을 나가는 경로 추가는 거부하고 저장소 밖 파일을 지킨다") {
        val origin = seedRepository(CHILD_FILE)
        val (work, sentinel) = repositoryBesideSentinel()
        openParent(work).use { fixture ->
            // `..` 없이도 밖으로 나간다 — 정규화가 링크를 따라가야만 드러나는 이탈이다.
            Files.createSymbolicLink(fixture.file(ESCAPING_LINK).toPath(), sentinel.parentFile.toPath())

            val violation = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.add(origin.absolutePath, ESCAPING_LINK)
            }

            violation.message.orEmpty() shouldContain ESCAPE_REASON
            sentinel.readText() shouldBe SENTINEL_CONTENT
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 한 단계가 실패해도 남은 보상을 모두 시도한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 절반만 적용된 상태를 만든다 — 워킹트리·하위 git 디렉터리·.gitmodules.
            val submoduleWork = fixture.file(SUBMODULE_PATH).also(File::mkdirs)
            File(submoduleWork, CHILD_FILE).writeText("절반만 붙은 내용\n")
            fixture.moduleGitDirectory(SUBMODULE_PATH).mkdirs()
            fixture.file(GIT_MODULES).writeText("[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")

            val failure = IOException("서브모듈 추가 실패")
            withoutWritePermission(submoduleWork) {
                rollback.restoreAfter(failure)

                failure.suppressed.toList().shouldNotBeEmpty()
                fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
                fixture.file(GIT_MODULES).exists() shouldBe false
            }
        }
    }

    test("추가 되돌리기는 .gitmodules 의 unstaged 수정을 다시 unstaged 로 되돌린다") {
        openParent(repositoryTrackingModules()).use { fixture ->
            fixture.file(GIT_MODULES).writeText(UNSTAGED_MODULES)
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.halfApplyModulesEntry()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.modulesText() shouldBe UNSTAGED_MODULES
            val status = fixture.status()
            status.modified shouldBe setOf(GIT_MODULES)
            status.changed.shouldBeEmpty()
            status.added.shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 워킹트리에서 지워진 .gitmodules 를 다시 지우고 인덱스 엔트리는 남긴다") {
        openParent(repositoryTrackingModules()).use { fixture ->
            fixture.file(GIT_MODULES).delete() shouldBe true
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.halfApplyModulesEntry()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.file(GIT_MODULES).exists() shouldBe false
            fixture.indexPaths() shouldBe listOf(GIT_MODULES, PARENT_FILE)
            val status = fixture.status()
            status.missing shouldBe setOf(GIT_MODULES)
            status.removed.shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 덮어쓴 gitlink 와 설정 섹션을 호출 전 값으로 되돌린다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val originalUrl = fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            val originalGitlink = fixture.gitlinkId(SUBMODULE_PATH).shouldNotBeNull()
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 남의 설정을 덮어쓰고 gitlink 까지 건드린, 절반만 적용된 상태.
            fixture.overwriteSubmoduleConfig()
            fixture.repository.removeIndexEntry(SUBMODULE_PATH)

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe originalUrl
            fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY).shouldBeNull()
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe originalGitlink
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("추가 되돌리기는 설정 저장이 실패하면 메모리 설정을 디스크에 맞추고 그 뒤 정리를 하지 않는다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.seedSubmoduleConfig()
            // 캡처 시점에는 gitlink 가 없다 — 되돌리기는 이 부재까지 되돌려야 한다.
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 남의 설정을 덮어쓰고 gitlink·워킹트리·하위 git 디렉터리·.gitmodules 까지 만든 상태.
            fixture.overwriteSubmoduleConfig()
            fixture.halfApplyGitlink()
            fixture.halfApplyAddArtifacts()

            val failure = IOException("서브모듈 추가 실패")
            fixture.withUnwritableConfig {
                rollback.restoreAfter(failure)

                failure.suppressed.toList().shouldNotBeEmpty()
                // 디스크에 못 썼으므로 메모리도 디스크와 같아야 한다 — 갈라진 채 두지 않는다.
                fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe FOREIGN_URL
                fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY) shouldBe "true"
            }
            // 설정을 못 되돌렸으면 gitlink 도 파일도 건드리지 않는다 — 부분 적용을 더 키우지 않는다.
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe FOREIGN_GITLINK
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
        }
    }

    test("설정 복원이 성공해야 추가 되돌리기가 나머지 보상을 진행한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.seedSubmoduleConfig()
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.overwriteSubmoduleConfig()
            fixture.halfApplyGitlink()
            fixture.halfApplyAddArtifacts()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe EXISTING_URL
            fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY).shouldBeNull()
            // 캡처 시점에 없던 gitlink 는 부재로 되돌아간다 — 남기면 인덱스가 유령 서브모듈을 가리킨다.
            fixture.gitlinkId(SUBMODULE_PATH).shouldBeNull()
            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
            fixture.file(GIT_MODULES).exists() shouldBe false
        }
    }

    test("이미 서브모듈이 있는 경로에 추가가 실패해도 호출 전 상태가 그대로 남는다") {
        val missingOrigin = File(tempdir(), "없는-원격").absolutePath
        openParent(repositoryWithSubmodule()).use { fixture ->
            val originalUrl = fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            val originalGitlink = fixture.gitlinkId(SUBMODULE_PATH).shouldNotBeNull()
            val originalModules = fixture.modulesText()

            shouldThrow<UndineException> { fixture.gateway.add(missingOrigin, SUBMODULE_PATH) }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe originalUrl
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe originalGitlink
            fixture.modulesText() shouldBe originalModules
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
            fixture.gateway.list() shouldHaveSize 1
        }
    }

    test("없는 서브모듈 초기화는 NotFound.SUBMODULE 로 보고한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val notFound = shouldThrow<UndineException.NotFound> {
                fixture.gateway.initialize(MISSING_PATH)
            }

            notFound.kind shouldBe UndineException.NotFound.Kind.SUBMODULE
            notFound.name shouldBe MISSING_PATH
        }
    }

    test("초기화는 조회와 실행 사이에 저장소 전환이 끼어도 다른 저장소를 건드리지 않는다") {
        val first = repositoryWithUninitializedSubmodule()
        val second = repositoryWithUninitializedSubmodule()
        switchRaceFixture(first, second).use { fixture ->
            fixture.open()

            fixture.raceSwitch { fixture.gateway.initialize(SUBMODULE_PATH) }

            File(first, "$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            File(second, "$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe false
            submodulesOf(second).single().state.initialized shouldBe false
        }
    }

    test("업데이트는 조회와 실행 사이에 저장소 전환이 끼어도 다른 저장소를 건드리지 않는다") {
        val first = repositoryWithSubmodule()
        val second = repositoryWithSubmodule()
        divergeSubmodule(first, SUBMODULE_PATH, CHILD_FILE)
        divergeSubmodule(second, SUBMODULE_PATH, CHILD_FILE)
        switchRaceFixture(first, second).use { fixture ->
            fixture.open()

            fixture.raceSwitch { fixture.gateway.update(SUBMODULE_PATH) }

            submodulesOf(first).single().state.divergedFromRecorded shouldBe false
            submodulesOf(second).single().state.divergedFromRecorded shouldBe true
        }
    }

    test("빈 경로는 사전조건 위반으로 거부한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            shouldThrow<IllegalArgumentException> { fixture.gateway.initialize(" ") }
            shouldThrow<IllegalArgumentException> { fixture.gateway.update(" ") }
        }
    }
})
