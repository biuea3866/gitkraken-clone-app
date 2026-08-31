package dev.undine.presentation

import dev.undine.domain.CommitId
import dev.undine.domain.DiffResult
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

private val COMMIT = CommitId.of("0".repeat(40))
private const val FILE_PATH = "src/main/kotlin/Undine.kt"
private val COMPUTED = DiffResult.Computed(hunks = emptyList())

/**
 * 파일 diff 읽기의 **실패 처리**를 배선 밖에서 검증한다.
 *
 * 읽지 못한 diff 를 빈 결과로 바꿔 내려보내면 상세 패널이 그 자리를 대신 채워 사용자는 파일이
 * 안 바뀐 줄로 안다. 그 구분이 조용히 사라지는 것을 여기서 막는다.
 */
class AppFileDiffSpec : BehaviorSpec({

    given("고른 커밋과 파일") {

        `when`("읽기가 성공하면") {
            then("읽은 diff 를 그대로 돌려주고 알릴 실패가 없다") {
                val errors = AppErrorState()

                val loaded = loadSelectedFileDiff(COMMIT, FILE_PATH, errors) { _, _ -> COMPUTED }

                loaded shouldBe COMPUTED
                errors.failure.shouldBeNull()
            }
        }

        `when`("읽기가 UndineException 으로 실패하면") {
            then("실패를 알리고 diff 는 비운다 — 변경 없음으로 바꾸지 않는다") {
                val errors = AppErrorState()

                val loaded = loadSelectedFileDiff(COMMIT, FILE_PATH, errors) { _, _ ->
                    throw UndineException.GitOperationFailed("diff.hunksOf")
                }

                loaded.shouldBeNull()
                errors.failure.shouldNotBeNull().kind shouldBe "GitOperationFailed"
            }
        }

        /**
         * 커밋·파일 선택이 바뀌면 앞선 읽기의 스코프가 취소된다. 그걸 실패로 잡아 보고하면 사용자가
         * 화면을 넘길 때마다 오류가 뜨고, 취소가 상위로 전파되지 않아 정리 경로가 끊긴다.
         */
        `when`("읽기가 취소되면") {
            then("취소를 삼키지 않고 그대로 전파하며 실패로 보고하지 않는다") {
                val errors = AppErrorState()

                shouldThrow<CancellationException> {
                    loadSelectedFileDiff(COMMIT, FILE_PATH, errors) { _, _ ->
                        throw CancellationException("파일 선택이 바뀌었습니다")
                    }
                }

                errors.failure.shouldBeNull()
            }
        }
    }

    given("고른 것이 없는 상태") {

        `when`("커밋이 없으면") {
            then("읽지 않고 비운다") {
                var attempted = false

                val loaded = loadSelectedFileDiff(null, FILE_PATH, AppErrorState()) { _, _ ->
                    attempted = true
                    COMPUTED
                }

                loaded.shouldBeNull()
                attempted shouldBe false
            }
        }

        `when`("파일이 없으면") {
            then("읽지 않고 비운다") {
                var attempted = false

                val loaded = loadSelectedFileDiff(COMMIT, null, AppErrorState()) { _, _ ->
                    attempted = true
                    COMPUTED
                }

                loaded.shouldBeNull()
                attempted shouldBe false
            }
        }
    }
})
