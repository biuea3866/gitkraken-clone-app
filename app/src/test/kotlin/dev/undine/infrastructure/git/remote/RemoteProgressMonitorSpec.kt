package dev.undine.infrastructure.git.remote

import dev.undine.domain.Progress
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class RemoteProgressMonitorSpec : FunSpec({

    test("첫 보고는 0.0 에서 시작하고 단계명을 그대로 전달한다") {
        val reported = mutableListOf<Progress>()
        val monitor = RemoteProgressMonitor(cancelled = { false }, onProgress = { reported += it })

        monitor.beginTask("Receiving objects", 10)

        reported.shouldNotBeEmpty()
        reported.first() shouldBe Progress(completedFraction = 0.0, phase = "Receiving objects")
    }

    test("작업량이 확정이면 완료 비율이 올라간다") {
        val reported = mutableListOf<Progress>()
        val monitor = RemoteProgressMonitor(cancelled = { false }, onProgress = { reported += it })

        monitor.beginTask("Receiving objects", 4)
        monitor.update(1)
        monitor.update(1)

        reported.last() shouldBe Progress(completedFraction = 0.5, phase = "Receiving objects")
    }

    test("작업량이 불확정이면 직전 비율을 유지한다") {
        val reported = mutableListOf<Progress>()
        val monitor = RemoteProgressMonitor(cancelled = { false }, onProgress = { reported += it })

        monitor.beginTask("Receiving objects", 4)
        monitor.update(2)
        monitor.endTask()
        monitor.beginTask("Counting objects", 0)
        monitor.update(7)

        reported.last() shouldBe Progress(completedFraction = 0.5, phase = "Counting objects")
    }

    test("진행률은 단계가 바뀌어도 감소하지 않는다") {
        val reported = mutableListOf<Progress>()
        val monitor = RemoteProgressMonitor(cancelled = { false }, onProgress = { reported += it })

        monitor.beginTask("Receiving objects", 2)
        monitor.update(2)
        monitor.endTask()
        monitor.beginTask("Checking out files", 10)
        monitor.update(1)

        reported.zipWithNext().all { (before, after) ->
            after.completedFraction >= before.completedFraction
        } shouldBe true
        reported.last().completedFraction shouldBe 1.0
    }

    test("보고된 완료 비율은 1.0 을 넘지 않는다") {
        val reported = mutableListOf<Progress>()
        val monitor = RemoteProgressMonitor(cancelled = { false }, onProgress = { reported += it })

        monitor.beginTask("Writing objects", 2)
        monitor.update(5)

        reported.last().completedFraction shouldBe 1.0
    }

    test("취소 여부는 주입된 판정을 그대로 따른다") {
        var cancelled = false
        val monitor = RemoteProgressMonitor(cancelled = { cancelled }, onProgress = { })

        monitor.isCancelled() shouldBe false
        cancelled = true
        monitor.isCancelled() shouldBe true
    }
})
