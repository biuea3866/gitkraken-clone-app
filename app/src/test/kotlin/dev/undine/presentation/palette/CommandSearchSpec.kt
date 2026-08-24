package dev.undine.presentation.palette

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

private val createBranch = testCommand("branch.create", "Create Branch")
private val deleteBranch = testCommand("branch.delete", "Delete Branch")
private val commitChanges = testCommand("commit.create", "Commit Changes")
private val allCommands = listOf(createBranch, deleteBranch, commitChanges)

private fun List<Command>.ids(): List<String> = map { it.id.value }

/** 팔레트 검색 — 부분 일치·약어 일치·최근 실행 가중. */
class CommandSearchSpec : FunSpec({

    test("빈 검색어는 등록된 모든 명령을 등록 순서대로 준다") {
        searchCommands(allCommands, query = "").ids() shouldContainExactly
            listOf("branch.create", "branch.delete", "commit.create")
    }

    test("공백만 있는 검색어도 전체 목록으로 본다") {
        searchCommands(allCommands, query = "   ").ids() shouldContainExactly
            listOf("branch.create", "branch.delete", "commit.create")
    }

    test("표시명 부분 일치로 명령을 찾는다") {
        searchCommands(allCommands, query = "branch").ids() shouldContainExactly
            listOf("branch.create", "branch.delete")
    }

    test("부분 일치는 대소문자를 가리지 않는다") {
        searchCommands(allCommands, query = "CoMmIt").ids() shouldContainExactly listOf("commit.create")
    }

    test("약어 cb 로 Create Branch 를 찾는다") {
        searchCommands(allCommands, query = "cb").ids() shouldContainExactly listOf("branch.create")
    }

    test("약어는 머리글자 순서를 지킨다") {
        searchCommands(allCommands, query = "bc").ids().shouldBeEmpty()
    }

    test("약어는 중간 단어를 건너뛰어도 순서만 맞으면 일치한다") {
        val createRemoteBranch = testCommand("branch.createRemote", "Create Remote Branch")

        searchCommands(listOf(createRemoteBranch), query = "cb").ids() shouldContainExactly
            listOf("branch.createRemote")
    }

    test("최근 실행한 명령이 같은 후보 안에서 앞에 온다") {
        val recent = listOf(CommandId("branch.delete"))

        searchCommands(allCommands, query = "branch", recentCommandIds = recent).ids() shouldContainExactly
            listOf("branch.delete", "branch.create")
    }

    test("최근 실행 목록의 순서대로 우선한다") {
        val recent = listOf(CommandId("commit.create"), CommandId("branch.delete"))

        searchCommands(allCommands, query = "", recentCommandIds = recent).ids() shouldContainExactly
            listOf("commit.create", "branch.delete", "branch.create")
    }

    test("최근 실행 이력이 없어 동점이면 등록 순서를 유지한다") {
        searchCommands(allCommands, query = "e", recentCommandIds = emptyList()).ids() shouldContainExactly
            listOf("branch.create", "branch.delete", "commit.create")
    }

    test("일치하는 명령이 없으면 빈 목록이다") {
        searchCommands(allCommands, query = "rebase").ids().shouldBeEmpty()
    }

    test("등록된 명령이 없으면 어떤 검색어에도 빈 목록이다") {
        searchCommands(emptyList(), query = "branch").shouldBeEmpty()
    }
})
