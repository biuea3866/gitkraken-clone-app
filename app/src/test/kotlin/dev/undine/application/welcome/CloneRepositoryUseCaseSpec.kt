package dev.undine.application.welcome

import dev.undine.domain.Progress
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

private val EXISTING = RepositoryPath("/tmp/already-open")
private const val REMOTE_URL = "https://example.invalid/undine.git"

/** 앱 전용 스테이징 디렉터리 이름 앞머리 — 구현과 같은 값을 관찰 가능한 계약으로 고정한다. */
private const val STAGING_PREFIX = ".undine-clone-"

/** 안내된 경로가 대상 자체가 아니라 **대상 옆의 앱 전용 스테이징 디렉터리**임을 단정한다. */
private fun RepositoryPath.shouldBeStagingOf(target: RepositoryPath) {
    val leftover = File(value)
    val expected = File(target.value).absoluteFile

    leftover.path shouldNotBe expected.path
    leftover.parentFile shouldBe expected.parentFile
    leftover.name shouldStartWith STAGING_PREFIX
}

/** 취소·정리 검증은 실제 임시 디렉터리로 한다 — 파일 시스템을 모킹하면 정리 여부를 증명하지 못한다. */
class CloneRepositoryUseCaseSpec : BehaviorSpec({

    given("존재하지 않는 대상 디렉터리") {
        `when`("clone 이 성공하면") {
            then("진행률이 순서대로 전달되고 최근 목록 맨 앞에 저장된다") {
                val target = RepositoryPath(File(tempdir(), "cloned").path)
                val settings = FakeSettingsGateway(settingsWith(listOf(EXISTING)))
                val updates = listOf(Progress(0.25, "Receiving objects"), Progress(1.0, "Resolving deltas"))
                val useCase = CloneRepositoryUseCase(FakeRemoteGateway(progressUpdates = updates), settings)
                val seen = mutableListOf<Progress>()

                val outcome = useCase.execute(REMOTE_URL, target, seen::add) { }

                outcome shouldBe CloneOutcome.Cloned(target)
                seen shouldContainExactly updates
                settings.stored.recentRepositories shouldContainExactly listOf(target, EXISTING)
                File(target.value).isDirectory shouldBe true
            }
        }

        `when`("clone 이 인증 실패로 끝나면") {
            then("앱이 만든 디렉터리를 지우고 예외를 올리며 최근 목록을 건드리지 않는다") {
                val target = RepositoryPath(File(tempdir(), "cloned").path)
                val settings = FakeSettingsGateway(settingsWith(listOf(EXISTING)))
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(failure = UndineException.AuthenticationFailed(remote = "origin")),
                    settings,
                )

                val failure = shouldThrow<UndineException.AuthenticationFailed> {
                    useCase.execute(REMOTE_URL, target, { }, { })
                }

                failure.message.orEmpty() shouldNotContain REMOTE_URL
                File(target.value).exists() shouldBe false
                settings.saveCount shouldBe 0
            }
        }
    }

    given("사용자가 미리 만들어 둔 빈 대상 디렉터리") {
        `when`("clone 이 실패하면") {
            then("앱이 만들지 않았으므로 디렉터리를 지우지 않는다") {
                val target = File(tempdir(), "prepared").apply { mkdirs() }
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(failure = UndineException.GitOperationFailed(operation = "clone")),
                    FakeSettingsGateway(),
                )

                shouldThrow<UndineException.GitOperationFailed> {
                    useCase.execute(REMOTE_URL, RepositoryPath(target.path), { }, { })
                }

                target.exists() shouldBe true
            }
        }
    }

    given("비어 있지 않은 대상 디렉터리") {
        `when`("clone 을 요청하면") {
            then("clone 을 시작하지 않고 거부하며 기존 내용을 남긴다") {
                val target = File(tempdir(), "occupied").apply { mkdirs() }
                val leftover = File(target, "README.md").apply { writeText("keep me") }
                val remote = FakeRemoteGateway()
                val settings = FakeSettingsGateway()

                val outcome = CloneRepositoryUseCase(remote, settings)
                    .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })

                outcome shouldBe CloneOutcome.TargetNotEmpty
                remote.cloneCount shouldBe 0
                settings.saveCount shouldBe 0
                leftover.readText() shouldBe "keep me"
            }
        }
    }

    given("대상 경로가 디렉터리가 아닌 일반 파일") {
        `when`("clone 을 요청하면") {
            then("덮어쓰지 않고 거부한다") {
                val target = File(tempdir(), "not-a-directory").apply { writeText("payload") }
                val remote = FakeRemoteGateway()

                val outcome = CloneRepositoryUseCase(remote, FakeSettingsGateway())
                    .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })

                outcome shouldBe CloneOutcome.TargetNotEmpty
                remote.cloneCount shouldBe 0
                target.readText() shouldBe "payload"
            }
        }
    }

    given("clone 중 대상 디렉터리에 사용자 파일이 생긴 경우") {
        `when`("clone 이 실패하면") {
            then("사용자가 넣은 파일을 지우지 않는다") {
                val target = File(tempdir(), "raced")
                val userFile = File(target, "notes.md")
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(
                        failure = UndineException.GitOperationFailed(operation = "clone"),
                        // clone 이 흐르는 동안 다른 주체가 대상 경로를 만들고 파일을 넣는 상황을 재현한다.
                        beforeFailure = {
                            target.mkdirs()
                            userFile.writeText("keep me")
                        },
                    ),
                    FakeSettingsGateway(),
                )

                shouldThrow<UndineException.GitOperationFailed> {
                    useCase.execute(REMOTE_URL, RepositoryPath(target.path), { }, { })
                }

                userFile.readText() shouldBe "keep me"
            }
        }
    }

    given("진행 중인 clone") {
        `when`("호출자가 코루틴을 취소하면") {
            then("CancellationException 이 그대로 올라오고 앱 전용 디렉터리만 정리된다") {
                val root = tempdir()
                val target = RepositoryPath(File(root, "cancelled").path)
                val settings = FakeSettingsGateway()
                val started = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(
                        progressUpdates = listOf(Progress(0.1, "Receiving objects")),
                        suspendUntil = never,
                    ),
                    settings,
                )
                var propagated: Throwable? = null

                val job = CoroutineScope(Dispatchers.Default).launch {
                    try {
                        useCase.execute(REMOTE_URL, target, { started.complete(Unit) }, { })
                    } catch (cancellation: CancellationException) {
                        // 삼키지 않고 다시 던진다 — 잡는 이유는 타입을 단정하기 위해서다.
                        propagated = cancellation
                        throw cancellation
                    }
                }
                started.await()
                // join 은 NonCancellable 정리까지 끝난 뒤에 돌아온다 — 그래서 파일 상태를 바로 검증할 수 있다.
                job.cancelAndJoin()

                job.isCancelled shouldBe true
                propagated.shouldBeInstanceOf<CancellationException>()
                File(target.value).exists() shouldBe false
                // 스테이징까지 지워져 대상 부모에 남는 것이 없다.
                root.list()?.toList() shouldContainExactly emptyList()
                settings.saveCount shouldBe 0
            }
        }

        `when`("취소 중 정리에 실패하면") {
            then("남은 앱 전용 경로를 알려 수동 정리를 안내한다") {
                val target = RepositoryPath(File(tempdir(), "cancelled-locked").path)
                val started = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(
                        progressUpdates = listOf(Progress(0.1, "Receiving objects")),
                        suspendUntil = never,
                    ),
                    FakeSettingsGateway(),
                    deleteDirectory = { false },
                )
                var reported: RepositoryPath? = null

                val job = CoroutineScope(Dispatchers.Default).launch {
                    useCase.execute(REMOTE_URL, target, { started.complete(Unit) }) { reported = it }
                }
                started.await()
                job.cancelAndJoin()

                reported.shouldNotBeNull().shouldBeStagingOf(target)
            }
        }
    }

    given("정리할 수 없는 대상 디렉터리") {
        `when`("clone 이 실패하면") {
            then("남은 경로를 알려 수동 정리를 안내한다") {
                val target = File(tempdir(), "locked")
                val useCase = CloneRepositoryUseCase(
                    FakeRemoteGateway(failure = UndineException.GitOperationFailed(operation = "clone")),
                    FakeSettingsGateway(),
                    // 정리 단계에서 실패를 재현한다 — 실제 삭제 실패(권한·잠금)는 OS 의존이라 재현이 불안정하다.
                    deleteDirectory = { false },
                )
                var reported: RepositoryPath? = null

                shouldThrow<UndineException.GitOperationFailed> {
                    useCase.execute(REMOTE_URL, RepositoryPath(target.path), { }) { reported = it }
                }

                reported.shouldNotBeNull().shouldBeStagingOf(RepositoryPath(target.path))
            }
        }
    }

    given("clone 결과 타입") {
        `when`("성공 결과를 만들면") {
            then("클론된 경로를 담는다") {
                val outcome: CloneOutcome = CloneOutcome.Cloned(EXISTING)

                outcome.shouldBeInstanceOf<CloneOutcome.Cloned>().path shouldBe EXISTING
            }
        }
    }

    given("성공 직전에 대상이 비어 있지 않은 디렉터리로 바뀐 경우") {
        `when`("promote 를 시도하면") {
            then("사용자 데이터를 지우지 않고 clone 을 실패로 돌리며 스테이징을 정리한다") {
                val parent = tempdir()
                val target = File(parent, "swapped")
                val userFile = File(target, "notes.md")
                val remote = FakeRemoteGateway(
                    // clone 이 끝난 직후, 옮기기 직전에 다른 주체가 대상을 채운 상황이다.
                    onClone = {
                        target.mkdirs()
                        userFile.writeText("keep me")
                    },
                )
                val settings = FakeSettingsGateway()

                shouldThrow<UndineException.GitOperationFailed> {
                    CloneRepositoryUseCase(remote, settings)
                        .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })
                }

                userFile.readText() shouldBe "keep me"
                settings.saveCount shouldBe 0
                parent.stagingLeftovers().shouldBeEmpty()
            }
        }
    }

    given("성공 직전에 대상이 일반 파일로 바뀐 경우") {
        `when`("promote 를 시도하면") {
            then("그 파일을 지우지 않고 실패로 돌린다") {
                val parent = tempdir()
                val target = File(parent, "file-now")
                val remote = FakeRemoteGateway(onClone = { target.writeText("payload") })
                val settings = FakeSettingsGateway()

                shouldThrow<UndineException.GitOperationFailed> {
                    CloneRepositoryUseCase(remote, settings)
                        .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })
                }

                target.readText() shouldBe "payload"
                settings.saveCount shouldBe 0
                parent.stagingLeftovers().shouldBeEmpty()
            }
        }
    }

    given("스테이징 경로가 정리 직전에 심볼릭 링크로 바뀐 경우") {
        `when`("실패 정리가 돌면") {
            then("링크를 따라가 지우지 않고 수동 정리로 넘긴다") {
                val parent = tempdir()
                val target = File(parent, "linked")
                // 링크가 가리킬 사용자 데이터. 링크를 따라 지우면 이 파일이 사라진다.
                val precious = File(parent, "precious").apply { mkdirs() }
                val preciousFile = File(precious, "data.txt").apply { writeText("do not delete") }

                var reported: RepositoryPath? = null
                val remote = FakeRemoteGateway(
                    failure = UndineException.GitOperationFailed(operation = "clone"),
                    beforeFailure = {
                        val staging = parent.stagingLeftovers().single()
                        staging.deleteRecursively()
                        Files.createSymbolicLink(staging.toPath(), precious.toPath())
                    },
                )

                shouldThrow<UndineException.GitOperationFailed> {
                    CloneRepositoryUseCase(remote, FakeSettingsGateway())
                        .execute(REMOTE_URL, RepositoryPath(target.path), { }, { reported = it })
                }

                preciousFile.readText() shouldBe "do not delete"
                reported.shouldNotBeNull().shouldBeStagingOf(RepositoryPath(target.path))
            }
        }
    }

    given("스테이징 안에 다른 곳을 가리키는 심볼릭 링크가 들어온 경우") {
        `when`("실패 정리가 돌면") {
            then("링크만 지우고 링크가 가리키는 내용은 남긴다") {
                val parent = tempdir()
                val target = File(parent, "with-link")
                val precious = File(parent, "precious").apply { mkdirs() }
                val preciousFile = File(precious, "data.txt").apply { writeText("do not delete") }

                val remote = FakeRemoteGateway(
                    failure = UndineException.GitOperationFailed(operation = "clone"),
                    beforeFailure = {
                        val staging = parent.stagingLeftovers().single()
                        Files.createSymbolicLink(File(staging, "link").toPath(), precious.toPath())
                    },
                )

                shouldThrow<UndineException.GitOperationFailed> {
                    CloneRepositoryUseCase(remote, FakeSettingsGateway())
                        .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })
                }

                preciousFile.readText() shouldBe "do not delete"
                parent.stagingLeftovers().shouldBeEmpty()
            }
        }
    }

    given("사용자가 미리 만든 빈 대상 디렉터리") {
        `when`("clone 이 성공하면") {
            then("그 디렉터리를 지우지 않고 안으로 내용을 옮긴다") {
                val parent = tempdir()
                val target = File(parent, "prepared").apply { mkdirs() }
                val identityBefore = target.fileKey()
                val remote = FakeRemoteGateway(
                    onClone = { into -> File(into.value, "HEAD").writeText("ref\n") },
                )
                val settings = FakeSettingsGateway()

                val outcome = CloneRepositoryUseCase(remote, settings)
                    .execute(REMOTE_URL, RepositoryPath(target.path), { }, { })

                outcome shouldBe CloneOutcome.Cloned(RepositoryPath(target.path))
                File(target, "HEAD").readText() shouldBe "ref\n"
                // 소유 표식은 앱 내부용이라 결과 저장소에 남지 않는다.
                target.list().orEmpty().toList() shouldContainExactly listOf("HEAD")
                // 디렉터리를 지우고 다시 만들었다면 지문이 달라진다 — 사용자가 건 권한·속성이 사라진다.
                target.fileKey() shouldBe identityBefore
                parent.stagingLeftovers().shouldBeEmpty()
            }
        }
    }

    given("정리 직전에 스테이징이 같은 이름의 다른 디렉터리로 교체된 경우") {
        `when`("실패 정리가 돌면") {
            then("이름이 같아도 앱이 만든 것이 아니므로 지우지 않는다") {
                val parent = tempdir()
                val target = File(parent, "swapped-staging")
                var impostorFile: File? = null
                var reported: RepositoryPath? = null

                val remote = FakeRemoteGateway(
                    failure = UndineException.GitOperationFailed(operation = "clone"),
                    beforeFailure = {
                        val staging = parent.stagingLeftovers().single()
                        staging.deleteRecursively()
                        // 같은 이름·같은 모양으로 다시 만든 남의 디렉터리. 이름 검사만으로는 통과한다.
                        staging.mkdirs()
                        impostorFile = File(staging, "someone-elses.txt").apply { writeText("keep me") }
                    },
                )

                shouldThrow<UndineException.GitOperationFailed> {
                    CloneRepositoryUseCase(remote, FakeSettingsGateway())
                        .execute(REMOTE_URL, RepositoryPath(target.path), { }, { reported = it })
                }

                impostorFile.shouldNotBeNull().readText() shouldBe "keep me"
                reported.shouldNotBeNull().shouldBeStagingOf(RepositoryPath(target.path))
            }
        }
    }

})

/** 대상 옆에 남은 앱 전용 스테이징 디렉터리들. 정리 여부를 파일 시스템에서 직접 확인한다. */
private fun File.stagingLeftovers(): List<File> =
    listFiles()?.filter { it.name.startsWith(STAGING_PREFIX) }.orEmpty()

/** 디렉터리 지문. 지우고 다시 만들면 달라진다 — 같은 경로가 같은 디렉터리라는 보장이 없다. */
private fun File.fileKey(): Any? =
    Files.readAttributes(toPath(), BasicFileAttributes::class.java).fileKey()
