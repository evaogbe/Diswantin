package io.github.evaogbe.diswantin.task.data

import java.time.Instant

data class EditTaskForm(
    private val name: String,
    private val deadline: Instant?,
    private val scheduledAt: Instant?,
    private val recurring: Boolean,
    private val existingTask: Task,
) {
    init {
        require(name.isNotBlank()) { "Name must be present" }
        require(deadline == null || scheduledAt == null) {
            "Must have only one of deadline and scheduledAt, but got $deadline and $scheduledAt"
        }
    }

    val updatedTask = existingTask.copy(
        name = name.trim(),
        deadline = deadline,
        scheduledAt = scheduledAt,
        recurring = recurring,
    )
}
