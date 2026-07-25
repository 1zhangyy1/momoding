package app.momoding.wire

internal class ReferenceProjectionStore : ProjectionTransactionStore {
    private val durable = linkedMapOf<String, DurableTaskProjection>()

    var failAtStage: ProjectionWriteStage? = null
    var failFinalCommit: Boolean = false
    var transactionCount: Int = 0
        private set
    var readCount: Int = 0
        private set
    var commitCount: Int = 0
        private set
    val checkpoints: MutableList<ProjectionWriteStage> = mutableListOf()

    fun state(taskId: String): DurableTaskProjection? = durable[taskId]

    override fun read(taskId: String): DurableTaskProjection? {
        readCount += 1
        return durable[taskId]
    }

    fun seed(state: DurableTaskProjection) {
        durable[state.taskId] = state
    }

    override fun <T> transaction(
        taskId: String,
        block: (ProjectionTransaction) -> T,
    ): T {
        transactionCount += 1
        val before = durable[taskId]
        var replacement: DurableTaskProjection? = null
        val transaction = object : ProjectionTransaction {
            override val current: DurableTaskProjection? = before

            override fun checkpoint(stage: ProjectionWriteStage) {
                checkpoints += stage
                if (failAtStage == stage) {
                    throw InjectedProjectionFailure("Injected failure at $stage")
                }
            }

            override fun replace(next: DurableTaskProjection) {
                check(next.taskId == taskId) { "Projection taskId does not match transaction key" }
                replacement = next
            }
        }

        val result = block(transaction)
        if (failFinalCommit) {
            throw InjectedProjectionFailure("Injected failure during final commit")
        }
        durable[taskId] = checkNotNull(replacement) {
            "Projection transaction returned without a replacement"
        }
        commitCount += 1
        return result
    }
}

internal class InjectedProjectionFailure(message: String) : RuntimeException(message)
