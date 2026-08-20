package dev.undine.infrastructure.git.remote

import dev.undine.domain.Progress
import org.eclipse.jgit.lib.EmptyProgressMonitor

/**
 * JGit [org.eclipse.jgit.lib.ProgressMonitor] 를 도메인 [Progress] 콜백으로 옮긴다.
 *
 * - `phase` 는 JGit 이 준 단계 제목(`Receiving objects` 등)을 **그대로** 전달한다.
 *   자체 enum 으로 매핑하면 JGit 이 단계를 추가할 때 조용히 빠진다.
 * - `completedFraction` 은 0.0 에서 시작하고 **감소하지 않는다.** 작업량이 불확정(`totalWork <= 0`)이면
 *   직전 값을 유지한다 — 단계마다 0 으로 되돌리면 사용자가 진행이 후퇴한 것으로 읽는다.
 * - [cancelled] 는 코루틴 취소 여부다. JGit 은 이 값이 참이 되면 전송을 중단한다.
 *
 * 진행률은 여러 단계를 합쳐 하나의 단조 증가 값으로 보고하므로, 첫 단계가 100% 에 닿으면
 * 이후 단계에서는 값이 더 오르지 않는다. 단계 전환은 `phase` 로 드러난다.
 */
internal class RemoteProgressMonitor(
    private val cancelled: () -> Boolean,
    private val onProgress: (Progress) -> Unit,
) : EmptyProgressMonitor() {

    private var phase: String = ""
    private var totalWork: Int = 0
    private var completedWork: Int = 0
    private var fraction: Double = 0.0

    override fun beginTask(title: String, totalWork: Int) {
        phase = title
        this.totalWork = totalWork
        completedWork = 0
        report()
    }

    override fun update(completed: Int) {
        completedWork += completed
        if (totalWork > 0) {
            val next = (completedWork.toDouble() / totalWork).coerceIn(0.0, 1.0)
            if (next > fraction) fraction = next
        }
        report()
    }

    override fun isCancelled(): Boolean = cancelled()

    private fun report() {
        onProgress(Progress(completedFraction = fraction, phase = phase))
    }
}
