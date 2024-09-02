package io.github.evaogbe.diswantin.task.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class EditTaskForm(
    private val name: String,
    private val deadlineDate: LocalDate?,
    private val deadlineTime: LocalTime?,
    private val scheduledAt: Instant?,
    private val recurring: Boolean,
    private val existingTask: Task,
) {
    init {
        require(name.isNotBlank()) { "Name must be present" }
        require((deadlineDate == null && deadlineTime == null) || scheduledAt == null) {
            """Must have only one of deadline and scheduledAt, but got 
                |deadlineDate: $deadlineDate, 
                |deadlineTime: $deadlineTime, and
                |scheduledAt: $scheduledAt""".trimMargin()
        }
    }

    val updatedTask = existingTask.copy(
        name = name.trim(),
        deadlineDate = deadlineDate,
        deadlineTime = deadlineTime,
        scheduledAt = scheduledAt,
        recurring = recurring,
    )
}
