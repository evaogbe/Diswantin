package io.github.evaogbe.diswantin.task.data

import java.time.Clock
import java.time.Instant

data class NewTaskForm(
    private val name: String,
    private val dueAt: Instant?,
    private val scheduledAt: Instant?,
    private val clock: Clock,
) {
    init {
        require(name.isNotBlank()) { "Name must be present" }
        require(dueAt == null || scheduledAt == null) {
            "Must have only one of dueAt and scheduledAt, but got $dueAt and $scheduledAt"
        }
    }

    val newTask
        get() = Task(
            createdAt = Instant.now(clock),
            name = name,
            dueAt = dueAt,
            scheduledAt = scheduledAt,
        )
}
