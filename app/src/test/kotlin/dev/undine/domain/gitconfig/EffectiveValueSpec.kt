package dev.undine.domain.gitconfig

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 실효값 모델 — 저장소 없이 검증하는 순수 로직이다 (테스트 규칙 3).
 *
 * `asBoolean()` 이 domain 에 있는 이유는 결정 G35 3 이다: 화면이 `"yes"` 를 직접 비교하면
 * Git 철자 지식이 presentation 으로 샌다.
 */
class EffectiveValueSpec : FunSpec({

    test("실효값은 raw 문자열과 출처를 그대로 보존한다") {
        val value = EffectiveValue(raw = "main", source = GitConfigSource.REPOSITORY)

        value.raw shouldBe "main"
        value.source shouldBe GitConfigSource.REPOSITORY
    }

    test("출처는 저장소·전역·시스템 셋이고 Git 우선순위 순으로 선언돼 있다") {
        // 우선순위 판단이 선언 순서에 기대므로, 순서가 바뀌면 여기서 먼저 깨져야 한다.
        GitConfigSource.entries shouldBe listOf(
            GitConfigSource.REPOSITORY,
            GitConfigSource.GLOBAL,
            GitConfigSource.SYSTEM,
        )
    }

    listOf("true", "yes", "on", "1", "TRUE", "Yes", "ON").forEach { raw ->
        test("Git 이 참으로 읽는 '$raw' 를 true 로 해석한다") {
            EffectiveValue(raw, GitConfigSource.GLOBAL).asBoolean() shouldBe true
        }
    }

    listOf("false", "no", "off", "0", "FALSE", "No", "Off").forEach { raw ->
        test("Git 이 거짓으로 읽는 '$raw' 를 false 로 해석한다") {
            EffectiveValue(raw, GitConfigSource.GLOBAL).asBoolean() shouldBe false
        }
    }

    listOf("maybe", "", "  ", "2").forEach { raw ->
        test("Boolean 으로 읽을 수 없는 '$raw' 는 판단 불가(null)이지 실패가 아니다") {
            EffectiveValue(raw, GitConfigSource.SYSTEM).asBoolean() shouldBe null
        }
    }

    test("조회 대상 키는 설정 화면이 다루는 아홉 개로 한정된다") {
        GitConfigKey.entries.map(GitConfigKey::qualifiedName) shouldBe listOf(
            "init.defaultBranch",
            "pull.rebase",
            "diff.tool",
            "merge.tool",
            "user.name",
            "user.email",
            "commit.gpgsign",
            "gpg.format",
            "user.signingkey",
        )
    }
})
