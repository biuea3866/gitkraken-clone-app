package dev.undine.infrastructure.git.submodule

import dev.undine.domain.RepositoryPath
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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

/*
 * 서브모듈 스펙들이 공유하는 실제 저장소 픽스처. 조회·추가는 [SubmoduleGatewayImplSpec], 제거는
 * [SubmoduleRemoveSpec] 이 쓴다 — 두 스펙이 같은 전제(같은 경로·같은 커밋 시각)를 봐야 한 쪽에서
 * 고친 전제가 다른 쪽에서 조용히 갈라지지 않는다. `diff` 패키지의 `DiffFixtures` 와 같은 배치다.
 */

internal const val MAIN = "main"
internal const val PARENT_FILE = "README.md"
internal const val CHILD_FILE = "lib.txt"
internal const val NESTED_FILE = "nested.txt"
internal const val SUBMODULE_PATH = "lib"
internal const val SECOND_SUBMODULE_PATH = "other-lib"
internal const val NESTED_PATH = "inner"
internal const val GIT_MODULES = ".gitmodules"
internal const val SUBMODULE_SECTION = "submodule"
internal const val MISSING_PATH = "없는모듈"
internal const val MODULES_BRANCH_ENTRY = "branch = "

internal const val CONFIG_URL_KEY = "url"
internal const val CONFIG_ACTIVE_KEY = "active"

/** 실패한 추가가 기존 설정 위에 덮어쓴 값 — 되돌리기가 이 값을 남기면 남의 설정을 바꾼 것이다. */
internal const val FOREIGN_URL = "https://example.invalid/덮어쓴.git"

/** 추가 이전부터 사용자가 갖고 있던 설정 — 되돌리기가 되돌려야 할 값이다. */
internal const val EXISTING_URL = "https://example.invalid/기존-설정.git"

/** 실패한 추가가 인덱스에 심어 둔 gitlink — 설정 복원이 실패하면 이 값이 그대로 남아야 한다. */
internal const val FOREIGN_GITLINK = "0123456789abcdef0123456789abcdef01234567"

/** 경로 이탈로 거부했음을 알리는 문구 — 미커밋·판정 불가와 사유가 섞이지 않는지 본다. */
internal const val ESCAPE_REASON = "허용된 디렉터리 밖"

/** 부모 저장소 **바깥**에 있는 사용자 디렉터리와 그 안의 파일 — 어떤 경로로도 지워져선 안 된다. */
internal const val OUTSIDE_DIRECTORY = "저장소-밖-사용자-디렉터리"
internal const val SENTINEL_FILE = "지켜야-하는-파일.txt"
internal const val SENTINEL_CONTENT = "저장소 밖 사용자 데이터\n"

/** 부모 워킹트리를 벗어나는 추가 경로 — 사용자 입력도 `.gitmodules` 와 같은 등급으로 다룬다. */
internal const val ESCAPING_ADD_PATH = "../$OUTSIDE_DIRECTORY"

/** 워킹트리 안에 있으나 저장소 밖을 가리키는 심볼릭 링크 이름. */
internal const val ESCAPING_LINK = "밖으로-나가는-링크"

/** 서브모듈 안의 추적되지 않은 파일 — 저장소 어디에도 사본이 없다. */
internal const val UNTRACKED_FILE = "추적되지-않은.txt"

/** `.gitignore` 가 가리는 파일 — `Status.isClean` 은 이것을 통과시킨다. */
internal const val IGNORED_FILE = "무시된.log"
internal const val IGNORE_RULE = "*.log\n"

/** 서브모듈과 **접두사만 겹치는** 형제 디렉터리 — 열거 밖이라 제거가 건드려선 안 된다. */
internal const val SIBLING_DIRECTORY = "lib-형제"

/** 판정 불가로 막혔음을 알리는 문구 — 미커밋·경로 이탈과 사유가 섞이지 않는지 본다. */
internal const val UNDECIDABLE_REASON = "판정 불가"

/** 파일은 깨끗하지만 부모 기록과 어긋난 HEAD 로 막혔음을 알리는 문구. */
internal const val DIVERGED_REASON = "기록된 커밋과 다른 HEAD"

/** 기록 커밋에서 도달할 수 없는 로컬 브랜치·태그로 막혔음을 알리는 문구. */
internal const val LOCAL_COMMIT_REASON = "되살릴 수 없는 로컬 커밋"

/** stash 엔트리로 막혔음을 알리는 문구 — 사용자가 해야 할 일이 달라 사유를 접지 않는다. */
internal const val STASHED_REASON = "stash 엔트리"

/** 서브모듈 안에만 있는 로컬 브랜치와 그 브랜치에서만 만든 파일 — 지우면 되찾을 길이 없다. */
internal const val LOCAL_BRANCH = "로컬-작업"
internal const val LOCAL_BRANCH_FILE = "로컬-브랜치.txt"
internal const val LIGHTWEIGHT_TAG = "로컬-경량-태그"
internal const val ANNOTATED_TAG = "로컬-주석-태그"
internal const val RECORDED_TAG = "기록-커밋-태그"
internal const val CUSTOM_LOCAL_REF = "refs/undine/local-work"

/** 이 추가와 무관하게 원래부터 추적되던 `.gitmodules` — 되돌리기가 여기까지 망가뜨리면 안 된다. */
internal const val COMMITTED_MODULES = "[submodule \"기존\"]\n\tpath = 기존\n"
internal const val UNSTAGED_MODULES = "$COMMITTED_MODULES\turl = https://example.invalid/기존.git\n"

/** 커밋 시각을 고정한다 — 테스트는 현재 시각에 의존하지 않는다. */
internal val FIXED_IDENT = PersonIdent(
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
internal class ParentFixture(val work: File, private val git: Git) : AutoCloseable {

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

internal suspend fun openParent(directory: File): ParentFixture =
    ParentFixture(directory, Git.open(directory)).also { it.open() }

/**
 * `stageModulesFile()`와 gitlink 갱신은 모두 인덱스 잠금을 얻어야 한다. 정확히 지정한 횟수의 잠금만
 * 실패시켜, 앞 단계의 변경이 보상되는지 실제 JGit 경로로 검증한다.
 */
internal class LockFailingRepository(gitDirectory: File) : FileRepository(gitDirectory) {

    private var lockAttempts = 0
    private var failureAttempt: Int? = null

    fun failOnLockAttempt(attempt: Int) {
        failureAttempt = attempt
    }

    override fun lockDirCache(): DirCache {
        lockAttempts += 1
        if (lockAttempts == failureAttempt) throw IOException("주입한 인덱스 잠금 실패")
        return super.lockDirCache()
    }
}

internal suspend fun openParentWithLockFailure(directory: File): Pair<ParentFixture, LockFailingRepository> {
    val repository = LockFailingRepository(File(directory, Constants.DOT_GIT))
    repository.incrementOpen()
    val fixture = ParentFixture(directory, Git.wrap(repository)).also { it.open() }
    return fixture to repository
}

/** 다른 저장소(예: 중첩 서브모듈)의 상태는 그 저장소를 대상으로 한 게이트웨이로 읽는다. */
internal suspend fun submodulesOf(directory: File): List<Submodule> {
    val access = GitAccess()
    access.open(RepositoryPath(directory.path)) { }
    return try {
        SubmoduleGatewayImpl(access).list()
    } finally {
        access.close()
    }
}

internal fun Git.commitFile(work: File, name: String, content: String, message: String) {
    File(work, name).writeText(content)
    add().addFilepattern(name).call()
    commit().setMessage(message).setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
}

internal fun Git.attachSubmodule(path: String, url: String) {
    submoduleAdd().setPath(path).setURI(url).call()?.use { /* 하위 저장소 핸들은 여기서 닫는다. */ }
    add().addFilepattern(GIT_MODULES).call()
    commit().setMessage("서브모듈 $path 추가").setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
}

internal fun TestConfiguration.seedRepository(fileName: String): File {
    val directory = tempdir()
    Git.init().setDirectory(directory).setInitialBranch(MAIN).call().use { git ->
        git.commitFile(directory, fileName, "$fileName 최초 내용\n", "최초 커밋")
    }
    return directory
}

internal fun TestConfiguration.cloneOf(source: File): File {
    val target = tempdir()
    Git.cloneRepository().setURI(source.absolutePath).setDirectory(target).call().close()
    return target
}

/** 서브모듈이 붙어 있고 이미 clone 까지 끝난 부모. */
internal fun TestConfiguration.repositoryWithSubmodule(): File {
    val origin = seedRepository(CHILD_FILE)
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { it.attachSubmodule(SUBMODULE_PATH, origin.absolutePath) }
    return parent
}

/** `.gitmodules`에 다른 선언이 남는 제거 경로 — 파일 저장·스테이징 분기를 실제로 지난다. */
internal fun TestConfiguration.repositoryWithTwoSubmodules(): File {
    val firstOrigin = seedRepository(CHILD_FILE)
    val secondOrigin = seedRepository("other.txt")
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { git ->
        git.attachSubmodule(SUBMODULE_PATH, firstOrigin.absolutePath)
        git.attachSubmodule(SECOND_SUBMODULE_PATH, secondOrigin.absolutePath)
    }
    return parent
}

/** 인덱스에는 서브모듈이 있으나 아직 clone 되지 않은 부모 — clone 직후의 정상 상태다. */
internal fun TestConfiguration.repositoryWithUninitializedSubmodule(): File = cloneOf(repositoryWithSubmodule())

/** 서브모듈이 다시 서브모듈을 가진 부모의 clone — 둘 다 미초기화 상태로 시작한다. */
internal fun TestConfiguration.repositoryWithNestedSubmodule(): File {
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
internal inline fun withoutWritePermission(directory: File, block: () -> Unit) {
    check(directory.setWritable(false)) { "테스트 전제: 디렉터리 쓰기 권한을 뺄 수 있어야 합니다." }
    try {
        block()
    } finally {
        directory.setWritable(true)
    }
}

/** `.gitmodules` 가 이미 추적되고 있는 부모 — 되돌리기가 호출 전 스테이징 상태를 지키는지 보는 전제다. */
internal fun TestConfiguration.repositoryTrackingModules(): File {
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { git -> git.commitFile(parent, GIT_MODULES, COMMITTED_MODULES, ".gitmodules 커밋") }
    return parent
}

/** 추가가 `.gitmodules` 를 고쳐 쓰고 스테이징까지 한, 절반만 적용된 상태를 만든다. */
internal fun ParentFixture.halfApplyModulesEntry() {
    file(GIT_MODULES).writeText("$UNSTAGED_MODULES[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")
    repository.stageModulesFile()
}

/** 추가 이전부터 있던 설정 섹션을 심는다 — "원래 있었다" 를 되돌리는 경로의 전제다. */
internal fun ParentFixture.seedSubmoduleConfig() {
    repository.config.setString(SUBMODULE_SECTION, SUBMODULE_PATH, CONFIG_URL_KEY, EXISTING_URL)
    repository.config.save()
}

/** 추가가 워킹트리·하위 git 디렉터리·`.gitmodules` 까지 절반만 만들어 둔 상태를 만든다. */
internal fun ParentFixture.halfApplyAddArtifacts() {
    File(file(SUBMODULE_PATH).also(File::mkdirs), CHILD_FILE).writeText("절반만 붙은 내용\n")
    moduleGitDirectory(SUBMODULE_PATH).mkdirs()
    file(GIT_MODULES).writeText("[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")
}

/**
 * 추가가 인덱스에 심어 둔 gitlink 엔트리를 만든다 — 되돌리기가 **인덱스까지** 호출 전 값으로
 * 되돌리는지 보는 전제다. 워킹트리·설정만 보면 gitlink 가 남거나 사라진 회귀를 놓친다.
 */
internal fun ParentFixture.halfApplyGitlink() {
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
internal fun ParentFixture.overwriteSubmoduleConfig() {
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
internal fun TestConfiguration.repositoryBesideSentinel(): Pair<File, File> {
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
internal inline fun ParentFixture.withUnwritableConfig(block: () -> Unit) {
    val lock = File(repository.directory, "config.lock")
    check(lock.mkdirs()) { "테스트 전제: config 잠금 자리를 막을 수 있어야 합니다." }
    try {
        block()
    } finally {
        deleteRecursively(lock)
    }
}

/** `.gitmodules.lock` 자리를 디렉터리로 막아, 다른 선언이 남은 경우의 [FileBasedConfig.save]만 실패시킨다. */
internal inline fun ParentFixture.withUnwritableModulesFile(block: () -> Unit) {
    val lock = File(file(GIT_MODULES).path + ".lock")
    check(lock.mkdirs()) { "테스트 전제: .gitmodules 잠금 자리를 막을 수 있어야 합니다." }
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
internal class IndexReadSignallingRepository(gitDirectory: File) : FileRepository(gitDirectory) {

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
internal const val SWITCH_QUEUE_MILLIS = 200L

/**
 * 저장소 **두 개**를 쥔 [GitAccess]. 조회와 실행 사이에 저장소 전환을 주입해, 한 논리 전이가
 * 하나의 임계구역 안에서 끝나는지 확인한다.
 *
 * [first] 를 연 채로 게이트웨이 연산을 시작하고, 그 연산이 대상을 조회하는 순간 [second] 로
 * 전환을 시도한다. 임계구역이 쪼개져 있으면 그 틈에 전환이 끼어 **[first] 에서 읽은 상태로
 * [second] 의 같은 경로**가 조작된다.
 */
internal class SwitchRaceFixture(
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
internal fun switchRaceFixture(first: File, second: File): SwitchRaceFixture {
    val firstRepository = IndexReadSignallingRepository(File(first, Constants.DOT_GIT))
    val secondRepository = FileRepository(File(second, Constants.DOT_GIT))
    firstRepository.incrementOpen()
    secondRepository.incrementOpen()
    return SwitchRaceFixture(first, second, firstRepository, secondRepository)
}

/** `.gitignore` 를 커밋해 둔 서브모듈이 붙은 부모 — "무시된 파일만 있는" 상태를 만들 수 있다. */
internal fun TestConfiguration.repositoryWithIgnoringSubmodule(): File {
    val origin = seedRepository(CHILD_FILE)
    Git.open(origin).use { git -> git.commitFile(origin, ".gitignore", IGNORE_RULE, "ignore 규칙 커밋") }
    val parent = seedRepository(PARENT_FILE)
    Git.open(parent).use { it.attachSubmodule(SUBMODULE_PATH, origin.absolutePath) }
    return parent
}

/** 저장소 **밖** sentinel 옆에 만든, 서브모듈이 붙은 부모 — 경로 이탈이 막히는지 보는 전제다. */
internal fun TestConfiguration.submoduleBesideSentinel(): Pair<File, File> {
    val origin = seedRepository(CHILD_FILE)
    val (work, sentinel) = repositoryBesideSentinel()
    Git.open(work).use { it.attachSubmodule(SUBMODULE_PATH, origin.absolutePath) }
    return work to sentinel
}

/**
 * `.gitmodules` 의 subsection **이름만** 바꾼다. 이름은 clone 해 온 남의 저장소가 정하는 값이라,
 * 그것이 삭제 범위를 정하게 두면 기준 디렉터리 밖이 지워진다.
 */
internal fun ParentFixture.renameModulesSection(name: String) {
    file(GIT_MODULES).writeText("[submodule \"$name\"]\n\tpath = $SUBMODULE_PATH\n\turl = ./원격\n")
}

/** 거부된 제거가 **아무것도** 건드리지 않았는지 — 설정·인덱스·워킹트리·하위 저장소를 함께 본다. */
internal fun ParentFixture.shouldKeepSubmoduleIntact() {
    configSubsections() shouldContain SUBMODULE_PATH
    indexPaths() shouldContain SUBMODULE_PATH
    file(SUBMODULE_PATH).exists() shouldBe true
    file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
    moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
}

/** 서브모듈 안에 커밋을 하나 더 쌓아 부모가 기록한 커밋과 어긋나게 만든다. */
internal fun divergeSubmodule(work: File, relativePath: String, fileName: String) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        git.commitFile(submoduleWork, fileName, "서브모듈에서 진행한 작업\n", "서브모듈 커밋")
    }
}

/**
 * 서브모듈 안에 **기록 커밋에서 도달할 수 없는 로컬 브랜치**를 남기고 HEAD 는 원래 자리로 되돌린다 —
 * 파일 상태는 깨끗해지지만 그 커밋은 이 서브모듈 안에만 있다.
 */
internal fun branchSubmoduleWork(work: File, relativePath: String) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        val original = git.repository.fullBranch
        git.checkout().setCreateBranch(true).setName(LOCAL_BRANCH).call()
        git.commitFile(submoduleWork, LOCAL_BRANCH_FILE, "다른 브랜치에서만 한 작업\n", "로컬 브랜치 커밋")
        git.checkout().setName(original).call()
    }
}

/**
 * 기록 커밋에서 도달할 수 없는 태그만 남긴다. 임시 브랜치는 지워 HEAD와 워킹트리는 원래 자리로
 * 되돌리므로, 태그 보존 검사가 없으면 로컬 커밋이 유실된다.
 */
internal fun tagSubmoduleWork(work: File, relativePath: String, tag: String, annotated: Boolean) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        val original = git.repository.fullBranch
        val temporaryBranch = "태그-원본-$tag"
        git.checkout().setCreateBranch(true).setName(temporaryBranch).call()
        git.commitFile(submoduleWork, "$tag.txt", "태그로만 남은 작업\n", "태그 원본 커밋")
        git.createTag(tag, annotated)
        git.checkout().setName(original).call()
        git.branchDelete().setBranchNames(temporaryBranch).setForce(true).call()
    }
}

/** 부모 gitlink가 기록한 커밋을 가리키는 태그는 제거를 막으면 안 된다. */
internal fun tagRecordedSubmoduleCommit(work: File, relativePath: String, tag: String, annotated: Boolean) {
    Git.open(File(work, relativePath)).use { git ->
        git.createTag(tag, annotated)
    }
}

/** 표준 namespace 밖의 ref도 로컬 커밋을 붙잡을 수 있으므로 보존 스캔에서 빠지면 안 된다. */
internal fun createCustomRefSubmoduleWork(work: File, relativePath: String) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        val original = git.repository.fullBranch
        val temporaryBranch = "사용자-ref-원본"
        git.checkout().setCreateBranch(true).setName(temporaryBranch).call()
        git.commitFile(submoduleWork, "custom-ref.txt", "사용자 ref로만 남은 작업\n", "사용자 ref 원본 커밋")
        git.repository.updateRef(CUSTOM_LOCAL_REF).apply {
            setNewObjectId(git.repository.resolve(Constants.HEAD))
            forceUpdate()
        }
        git.checkout().setName(original).call()
        git.branchDelete().setBranchNames(temporaryBranch).setForce(true).call()
    }
}

private fun Git.createTag(name: String, annotated: Boolean) {
    tag().setName(name).setAnnotated(annotated).also { command ->
        if (annotated) command.setTagger(FIXED_IDENT)
    }.call()
}

/** 서브모듈 안에 stash 엔트리를 남긴다 — 워킹트리는 다시 깨끗해지고 커밋은 `refs/stash` 에만 남는다. */
internal fun stashSubmoduleWork(work: File, relativePath: String) {
    val submoduleWork = File(work, relativePath)
    Git.open(submoduleWork).use { git ->
        File(submoduleWork, CHILD_FILE).writeText("stash 로 치워 둔 작업\n")
        git.stashCreate().setPerson(FIXED_IDENT).call()
    }
}

/** 서브모듈의 워킹트리가 실제로 깨끗한지 — 커밋 상태만으로 막혔음을 보이는 전제다. */
internal fun ParentFixture.shouldHaveCleanSubmoduleWorkTree() {
    Git.open(file(SUBMODULE_PATH)).use { git -> git.status().call().isClean shouldBe true }
}
