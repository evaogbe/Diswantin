package io.github.evaogbe.diswantin.task.data

import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface TaskRepository {
    fun getCurrentTask(scheduledBefore: Instant, doneBefore: Instant): Flow<Task?>

    fun getById(id: Long): Flow<Task>

    fun getTaskDetailById(id: Long): Flow<TaskDetail?>

    fun search(query: String): Flow<List<Task>>

    suspend fun create(form: NewTaskForm): Task

    suspend fun update(form: EditTaskForm): Task

    suspend fun delete(id: Long)

    suspend fun markDone(id: Long)
}
