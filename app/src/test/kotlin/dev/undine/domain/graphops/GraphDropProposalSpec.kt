package dev.undine.domain.graphops

import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val MAIN_HEAD = CommitId.of("a".repeat(40))
private val FEATURE_HEAD = CommitId.of("b".repeat(40))
private val OLD_COMMIT = CommitId.of("c".repeat(40))

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val RELEASE_TAG = RefName("v1.0.0")

private fun branchSource(name: RefName, target: CommitId) = GraphDragSource.Branch(name, target)

private fun lightweightTag(target: CommitId) = GraphDragSource.Tag(RELEASE_TAG, target, isAnnotated = false)

private fun choicesOf(proposal: GraphDropProposal): List<GraphOperation> =
    proposal.shouldBeInstanceOf<GraphDropProposal.Available>().choices

private fun refusalOf(proposal: GraphDropProposal): GraphDropRefusal =
    proposal.shouldBeInstanceOf<GraphDropProposal.Unavailable>().reason

/**
 * 드래그 소스 × 드롭 대상 → 제안. 그래프 조작의 실제 규칙이라 제스처·화면과 떼어 검증한다
 * ([dev.undine.presentation.rebase.dropTargetIndex] 선례와 같은 이유).
 */
class GraphDropProposalSpec : BehaviorSpec({

    given("브랜치를 다른 브랜치 위로 끌었을 때") {
        val proposal = proposeGraphDrop(
            source = branchSource(FEATURE, FEATURE_HEAD),
            target = GraphDropTarget.Branch(MAIN, MAIN_HEAD),
        )

        `when`("제안을 만들면") {
            val choices = choicesOf(proposal)

            then("병합과 리베이스 둘 다 고를 수 있다") {
                choices shouldContainExactly listOf(
                    GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)),
                    GraphOperation.Rebase(branch = BranchTarget.Named(FEATURE), upstream = MAIN),
                )
            }

            then("병합은 드롭 대상에서, 리베이스는 드래그 소스에서 수행한다 — 수행 브랜치가 서로 반대다") {
                val merge = choices.filterIsInstance<GraphOperation.Merge>().single()
                val rebase = choices.filterIsInstance<GraphOperation.Rebase>().single()

                merge.into shouldBe BranchTarget.Named(MAIN)
                rebase.branch shouldBe BranchTarget.Named(FEATURE)
            }

            then("둘 다 파괴적이지 않아 위험 경고 없이 확인만 받는다") {
                choices.none { it.isDestructive } shouldBe true
            }
        }
    }

    given("같은 브랜치 위로 끌었을 때") {
        `when`("제안을 만들면") {
            val proposal = proposeGraphDrop(
                source = branchSource(MAIN, MAIN_HEAD),
                target = GraphDropTarget.Branch(MAIN, MAIN_HEAD),
            )

            then("드롭할 수 없다 — 자기 자신에 병합·재배치할 수 없다") {
                refusalOf(proposal) shouldBe GraphDropRefusal.SAME_REF
            }
        }
    }

    given("커밋을 브랜치 위로 끌었을 때") {
        `when`("제안을 만들면") {
            val proposal = proposeGraphDrop(
                source = GraphDragSource.Commit(OLD_COMMIT),
                target = GraphDropTarget.Branch(MAIN, MAIN_HEAD),
            )

            then("그 브랜치에 cherry-pick 하나만 제안한다") {
                choicesOf(proposal) shouldContainExactly listOf(
                    GraphOperation.CherryPick(commit = OLD_COMMIT, onto = BranchTarget.Named(MAIN)),
                )
            }
        }
    }

    given("브랜치를 커밋 위로 끌었을 때") {
        `when`("다른 커밋이면") {
            val proposal = proposeGraphDrop(
                source = branchSource(MAIN, MAIN_HEAD),
                target = GraphDropTarget.Commit(OLD_COMMIT),
            )
            val choices = choicesOf(proposal)

            then("그 브랜치를 그 커밋으로 되돌리는 reset 하나를 제안한다") {
                choices shouldContainExactly listOf(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))
            }

            then("파괴적이라고 표시해 화면이 위험 경고를 붙일 수 있다") {
                choices.single().isDestructive shouldBe true
            }
        }

        `when`("이미 그 커밋을 가리키고 있으면") {
            val proposal = proposeGraphDrop(
                source = branchSource(MAIN, MAIN_HEAD),
                target = GraphDropTarget.Commit(MAIN_HEAD),
            )

            then("바뀔 것이 없어 드롭할 수 없다") {
                refusalOf(proposal) shouldBe GraphDropRefusal.SAME_COMMIT
            }
        }
    }

    given("태그를 커밋 위로 끌었을 때") {
        `when`("lightweight 태그면") {
            val proposal = proposeGraphDrop(
                source = lightweightTag(OLD_COMMIT),
                target = GraphDropTarget.Commit(MAIN_HEAD),
            )

            then("태그 이동 하나를 제안한다") {
                choicesOf(proposal) shouldContainExactly listOf(
                    GraphOperation.MoveTag(tag = RELEASE_TAG, to = MAIN_HEAD),
                )
            }
        }

        `when`("annotated 태그면") {
            val proposal = proposeGraphDrop(
                source = GraphDragSource.Tag(RELEASE_TAG, OLD_COMMIT, isAnnotated = true),
                target = GraphDropTarget.Commit(MAIN_HEAD),
            )

            then("메시지와 tagger 를 잃으므로 드롭할 수 없다") {
                refusalOf(proposal) shouldBe GraphDropRefusal.ANNOTATED_TAG
            }
        }

        `when`("이미 그 커밋을 가리키고 있으면") {
            val proposal = proposeGraphDrop(
                source = lightweightTag(MAIN_HEAD),
                target = GraphDropTarget.Commit(MAIN_HEAD),
            )

            then("바뀔 것이 없어 드롭할 수 없다") {
                refusalOf(proposal) shouldBe GraphDropRefusal.SAME_COMMIT
            }
        }
    }

    given("지원하지 않는 조합") {
        `when`("태그를 브랜치 위로 끌면") {
            then("드롭할 수 없다") {
                refusalOf(
                    proposeGraphDrop(
                        source = lightweightTag(OLD_COMMIT),
                        target = GraphDropTarget.Branch(MAIN, MAIN_HEAD),
                    ),
                ) shouldBe GraphDropRefusal.UNSUPPORTED_COMBINATION
            }
        }

        `when`("커밋을 커밋 위로 끌면") {
            then("드롭할 수 없다") {
                refusalOf(
                    proposeGraphDrop(
                        source = GraphDragSource.Commit(OLD_COMMIT),
                        target = GraphDropTarget.Commit(MAIN_HEAD),
                    ),
                ) shouldBe GraphDropRefusal.UNSUPPORTED_COMBINATION
            }
        }
    }

    given("제안 자체") {
        `when`("가능 여부를 물으면") {
            then("가능한 조합만 드롭 가능이다") {
                proposeGraphDrop(
                    branchSource(FEATURE, FEATURE_HEAD),
                    GraphDropTarget.Branch(MAIN, MAIN_HEAD),
                ).canDrop shouldBe true

                proposeGraphDrop(
                    branchSource(MAIN, MAIN_HEAD),
                    GraphDropTarget.Branch(MAIN, MAIN_HEAD),
                ).canDrop shouldBe false
            }
        }
    }
})
