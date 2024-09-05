package io.github.evaogbe.diswantin.testing

import io.github.evaogbe.diswantin.task.data.CurrentTaskParams
import io.github.evaogbe.diswantin.task.data.EditTaskForm
import io.github.evaogbe.diswantin.task.data.NewTaskForm
import io.github.evaogbe.diswantin.task.data.Task
import io.github.evaogbe.diswantin.task.data.TaskCompletion
import io.github.evaogbe.diswantin.task.data.TaskDetail
import io.github.evaogbe.diswantin.task.data.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KFunction

class FakeTaskRepository(private val db: FakeDatabase = FakeDatabase()) : TaskRepository {
    private val throwingMethods = MutableStateFlow(setOf<KFunction<*>>())

    val tasks
        get() = db.taskTable.value.values

    override fun getCurrentTask(params: CurrentTaskParams): Flow<Task?> =
        combine(
            throwingMethods,
            db.taskTable,
            db.taskPathTable,
            db.taskCompletionTable,
        ) { throwingMethods, tasks, taskPaths, taskCompletions ->
            if (::getCurrentTask in throwingMethods) {
                throw RuntimeException("Test")
            }

            tasks.values
                .sortedWith(
                    compareBy(nullsLast(), Task::scheduledAt)
                        .thenComparing({
                            when {
                                it.deadlineDate != null && it.deadlineTime != null -> {
                                    it.deadlineDate
                                        .atTime(it.deadlineTime)
                                        .atZone(ZoneId.systemDefault())
                                }

                                it.deadlineDate != null -> {
                                    it.deadlineDate
                                        .atTime(LocalTime.MAX)
                                        .atZone(ZoneId.systemDefault())
                                }

                                it.deadlineTime != null -> ZonedDateTime.now().with(it.deadlineTime)
                                it.recurring -> params.recurringDeadline
                                else -> null
                            }
                        }, nullsLast())
                        .thenComparing(Task::recurring, reverseOrder())
                        .thenComparing(Task::createdAt)
                        .thenComparing(Task::id)
                )
                .mapNotNull { task ->
                    taskPaths.values
                        .filter { path ->
                            val doneAt = taskCompletions.values
                                .filter { it.taskId == path.ancestor }
                                .maxOfOrNull { it.doneAt }
                            path.descendant == task.id && tasks[path.ancestor]?.let {
                                doneAt == null || it.recurring && doneAt < params.doneBefore
                            } == true
                        }
                        .maxByOrNull { it.depth }
                        ?.let { tasks[it.ancestor] }
                }.firstOrNull { task ->
                    task.scheduledAt?.let { it <= params.scheduledBefore } != false
                }
        }

    override fun getById(id: Long): Flow<Task> =
        combine(throwingMethods, db.taskTable) { throwingMethods, tasks ->
            if (::getById in throwingMethods) {
                throw RuntimeException("Test")
            }

            checkNotNull(tasks[id])
        }

    override fun getTaskDetailById(id: Long): Flow<TaskDetail?> =
        combine(
            throwingMethods,
            db.taskCategoryTable,
            db.taskTable,
            db.taskCompletionTable,
            db.taskPathTable,
        ) { throwingMethods, taskCategories, tasks, taskCompletions, taskPaths ->
            if (::getTaskDetailById in throwingMethods) {
                throw RuntimeException("Test")
            }

            val parentId =
                taskPaths.values.firstOrNull { it.descendant == id && it.depth == 1 }?.ancestor
            tasks[id]?.let { task ->
                TaskDetail(
                    id = task.id,
                    name = task.name,
                    deadlineDate = task.deadlineDate,
                    deadlineTime = task.deadlineTime,
                    scheduledAt = task.scheduledAt,
                    recurring = task.recurring,
                    doneAt = taskCompletions.values
                        .filter { it.taskId == task.id }
                        .maxOfOrNull { it.doneAt },
                    categoryId = task.categoryId,
                    categoryName = task.categoryId?.let { taskCategories[it] }?.name,
                    parentId = parentId,
                    parentName = parentId?.let { tasks[it] }?.name
                )
            }
        }

    override fun search(query: String): Flow<List<Task>> =
        combine(throwingMethods, db.taskTable) { throwingMethods, tasks ->
            if (::search in throwingMethods) {
                throw RuntimeException("Test")
            }

            tasks.values.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }

    override suspend fun create(form: NewTaskForm): Task {
        if (::create in throwingMethods.value) {
            throw RuntimeException("Test")
        }

        return db.insertTask(form.newTask)
    }

    override suspend fun update(form: EditTaskForm): Task {
        if (::update in throwingMethods.value) {
            throw RuntimeException("Test")
        }

        db.updateTask(form.updatedTask)
        return form.updatedTask
    }

    override suspend fun delete(id: Long) {
        if (::delete in throwingMethods.value) {
            throw RuntimeException("Test")
        }

        db.deleteTask(id)
    }

    override suspend fun markDone(id: Long) {
        if (::markDone in throwingMethods.value) {
            throw RuntimeException("Test")
        }

        db.insertTaskCompletion(TaskCompletion(taskId = id, doneAt = Instant.now()))
    }

    override suspend fun addParent(id: Long, parentId: Long) {
        if (::addParent in throwingMethods.value) {
            throw RuntimeException("Test")
        }

        db.connectTaskPath(parentId = parentId, childId = id)
    }

    fun setThrows(method: KFunction<*>, shouldThrow: Boolean) {
        if (shouldThrow) {
            throwingMethods.update { it + method }
        } else {
            throwingMethods.update { it - method }
        }
    }

    companion object {
        fun withTasks(vararg initialTasks: Task) = withTasks(initialTasks.toSet())

        fun withTasks(initialTasks: Iterable<Task>): FakeTaskRepository {
            val db = FakeDatabase()
            initialTasks.forEach(db::insertTask)
            return FakeTaskRepository(db)
        }
    }
}
