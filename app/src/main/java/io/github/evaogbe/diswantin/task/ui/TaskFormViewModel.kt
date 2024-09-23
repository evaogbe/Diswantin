package io.github.evaogbe.diswantin.task.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.evaogbe.diswantin.R
import io.github.evaogbe.diswantin.data.Result
import io.github.evaogbe.diswantin.data.getOrDefault
import io.github.evaogbe.diswantin.data.weekOfMonthField
import io.github.evaogbe.diswantin.task.data.EditTaskForm
import io.github.evaogbe.diswantin.task.data.NewTaskForm
import io.github.evaogbe.diswantin.task.data.PathUpdateType
import io.github.evaogbe.diswantin.task.data.RecurrenceType
import io.github.evaogbe.diswantin.task.data.Task
import io.github.evaogbe.diswantin.task.data.TaskCategory
import io.github.evaogbe.diswantin.task.data.TaskCategoryRepository
import io.github.evaogbe.diswantin.task.data.TaskRecurrence
import io.github.evaogbe.diswantin.task.data.TaskRepository
import io.github.evaogbe.diswantin.ui.navigation.NavArguments
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TaskFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val taskCategoryRepository: TaskCategoryRepository,
    private val clock: Clock,
    val locale: Locale,
) : ViewModel() {
    private val taskId: Long? = savedStateHandle[NavArguments.ID_KEY]

    val isNew = taskId == null

    var nameInput by mutableStateOf(savedStateHandle[NavArguments.NAME_KEY] ?: "")
        private set

    private val deadlineDate = MutableStateFlow<LocalDate?>(null)

    private val deadlineTime = MutableStateFlow<LocalTime?>(null)

    private val scheduledDate = MutableStateFlow<LocalDate?>(null)

    private val scheduledTime = MutableStateFlow<LocalTime?>(null)

    private val recurrenceUiState = MutableStateFlow<TaskRecurrenceUiState?>(null)

    private val category = MutableStateFlow<TaskCategory?>(null)

    private val categoryQuery = MutableStateFlow("")

    private val parentTask = MutableStateFlow<Task?>(null)

    private val parentTaskQuery = MutableStateFlow("")

    private val saveResult = MutableStateFlow<Result<Unit>?>(null)

    private val userMessage = MutableStateFlow<Int?>(null)

    private val hasCategoriesStream = taskCategoryRepository.hasCategoriesStream
        .map<Boolean, Result<Boolean>> { Result.Success(it) }
        .catch { e ->
            Timber.e(e, "Failed to query has categories")
            emit(Result.Failure)
        }

    private val taskCountStream = taskRepository.getCount()
        .map<Long, Result<Long>> { Result.Success(it) }
        .catch { e ->
            Timber.e(e, "Failed to fetch task count")
            emit(Result.Failure)
        }

    private val existingTaskStream = taskId?.let { id ->
        taskRepository.getById(id)
            .map<Task, Result<Task>> { Result.Success(it) }
            .catch { e ->
                Timber.e(e, "Failed to fetch task by id: %d", id)
                emit(Result.Failure)
            }
    } ?: flowOf(Result.Success(null))

    private val existingRecurrencesStream = taskId?.let { id ->
        taskRepository.getTaskRecurrencesByTaskId(id)
            .map<List<TaskRecurrence>, Result<List<TaskRecurrence>>> { Result.Success(it) }
            .catch { e ->
                Timber.e(e, "Failed to fetch task recurrences by task id: %d", id)
                emit(Result.Failure)
            }
    } ?: flowOf(Result.Success(emptyList()))

    private val existingCategoryStream = taskId?.let { id ->
        taskCategoryRepository.getByTaskId(id)
            .map<TaskCategory?, Result<TaskCategory?>> { Result.Success(it) }
            .catch { e ->
                Timber.e(e, "Failed to fetch task category by task id: %d", id)
                emit(Result.Failure)
            }
    } ?: flowOf(Result.Success(null))

    private val existingParentTaskStream = taskId?.let { id ->
        taskRepository.getParent(id)
            .map<Task?, Result<Task?>> { Result.Success(it) }
            .catch { e ->
                Timber.e(e, "Failed to fetch parent task by child id: %d", id)
                emit(Result.Failure)
            }
    } ?: flowOf(Result.Success(null))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    val uiState = combine(
        deadlineDate,
        deadlineTime,
        scheduledDate,
        scheduledTime,
        recurrenceUiState,
        hasCategoriesStream,
        category,
        categoryQuery,
        categoryQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                taskCategoryRepository.search(query.trim()).catch { e ->
                    Timber.e(e, "Failed to search for task category by query: %s", query)
                    userMessage.value = R.string.search_task_category_options_error
                }
            }
        },
        taskCountStream,
        parentTask,
        parentTaskQuery,
        parentTaskQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                taskRepository.search(query.trim()).catch { e ->
                    Timber.e(e, "Failed to search for task by query: %s", query)
                    userMessage.value = R.string.search_task_options_error
                }
            }
        },
        saveResult,
        userMessage,
        existingTaskStream,
        existingRecurrencesStream,
        existingCategoryStream,
        existingParentTaskStream,
    ) { args ->
        val deadlineDate = args[0] as LocalDate?
        val deadlineTime = args[1] as LocalTime?
        val scheduledDate = args[2] as LocalDate?
        val scheduledTime = args[3] as LocalTime?
        val recurrenceUiState = args[4] as TaskRecurrenceUiState?
        val hasCategories = args[5] as Result<Boolean>
        val category = args[6] as TaskCategory?
        val categoryQuery = (args[7] as String).trim()
        val categoryOptions = args[8] as List<TaskCategory>
        val taskCount = args[9] as Result<Long>
        val parentTask = args[10] as Task?
        val parentTaskQuery = (args[11] as String).trim()
        val parentTaskOptions = args[12] as List<Task>
        val saveResult = args[13] as Result<Unit>?
        val userMessage = args[14] as Int?
        val existingTask = args[15] as Result<Task?>
        val existingRecurrences = args[16] as Result<List<TaskRecurrence>>
        val existingCategory = args[17] as Result<TaskCategory?>
        val existingParentTask = args[18] as Result<Task?>

        when {
            saveResult is Result.Success -> TaskFormUiState.Saved
            existingTask is Result.Success && existingRecurrences is Result.Success -> {
                TaskFormUiState.Success(
                    deadlineDate = deadlineDate,
                    deadlineTime = deadlineTime,
                    scheduledDate = scheduledDate,
                    scheduledTime = scheduledTime,
                    recurrence = recurrenceUiState,
                    showCategoryField = existingCategory is Result.Success &&
                            hasCategories.getOrDefault(false),
                    category = category,
                    categoryOptions = if (
                        categoryQuery == category?.name &&
                        categoryQuery == categoryOptions.singleOrNull()?.name
                    ) {
                        persistentListOf()
                    } else {
                        categoryOptions.toPersistentList()
                    },
                    showParentTaskField = existingParentTask is Result.Success &&
                            taskCount.getOrDefault(0L) > if (taskId == null) 0L else 1L,
                    parentTask = parentTask,
                    parentTaskOptions = if (
                        parentTaskQuery == parentTask?.name &&
                        parentTaskQuery == parentTaskOptions.singleOrNull()?.name
                    ) {
                        persistentListOf()
                    } else {
                        parentTaskOptions.toPersistentList()
                    },
                    hasSaveError = saveResult is Result.Failure,
                    userMessage = userMessage,
                )
            }

            else -> TaskFormUiState.Failure
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TaskFormUiState.Pending,
    )

    val recurrenceUiStateOrDefault = recurrenceUiState.map {
        it ?: TaskRecurrenceUiState(
            start = LocalDate.now(clock),
            type = RecurrenceType.Day,
            step = 1,
            weekdays = persistentSetOf(),
            locale = locale,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TaskRecurrenceUiState(
            start = LocalDate.now(clock),
            type = RecurrenceType.Day,
            step = 1,
            weekdays = persistentSetOf(),
            locale = locale,
        ),
    )

    val currentWeekday = LocalDate.now(clock).dayOfWeek

    init {
        viewModelScope.launch {
            val existingTask = existingTaskStream.first().getOrNull() ?: return@launch
            nameInput = existingTask.name
            deadlineDate.value = existingTask.deadlineDate
            deadlineTime.value = existingTask.deadlineTime
            scheduledDate.value = existingTask.scheduledDate
            scheduledTime.value = existingTask.scheduledTime
            recurrenceUiState.value = existingRecurrencesStream.first().getOrNull()?.let {
                TaskRecurrenceUiState.tryFromEntities(it, locale)
            }
            category.value = existingCategoryStream.first().getOrNull()
            parentTask.value = existingParentTaskStream.first().getOrNull()
        }
    }

    fun updateNameInput(value: String) {
        nameInput = value
    }

    fun updateDeadlineDate(value: LocalDate?) {
        deadlineDate.value = value
    }

    fun updateDeadlineTime(value: LocalTime?) {
        deadlineTime.value = value
    }

    fun updateScheduledDate(value: LocalDate?) {
        scheduledDate.value = value
        if (scheduledTime.value == null) {
            scheduledTime.value = LocalTime.of(9, 0)
        }
    }

    fun updateScheduledTime(value: LocalTime?) {
        scheduledTime.value = value
        if (value == null) {
            scheduledDate.value = null
        }
    }

    fun updateCategory(value: TaskCategory?) {
        category.value = value
    }

    fun updateParentTask(value: Task?) {
        parentTask.value = value
    }

    fun updateRecurrence(value: TaskRecurrenceUiState?) {
        recurrenceUiState.value =
            if (value?.type == RecurrenceType.Week && value.weekdays.isEmpty()) {
                value.copy(weekdays = persistentSetOf(value.start.dayOfWeek))
            } else {
                value
            }
    }

    fun searchCategories(query: String) {
        categoryQuery.value = query
    }

    fun searchTasks(query: String) {
        parentTaskQuery.value = query
    }

    fun saveTask() {
        if (nameInput.isBlank()) return
        val state = (uiState.value as? TaskFormUiState.Success) ?: return
        val deadlineDate = if (
            state.deadlineDate == null &&
            state.deadlineTime != null &&
            state.recurrence == null
        ) {
            LocalDate.now()
        } else {
            state.deadlineDate
        }
        val scheduledDate = if (
            state.scheduledDate == null &&
            state.scheduledTime != null &&
            state.recurrence == null
        ) {
            LocalDate.now()
        } else {
            state.scheduledDate
        }
        val recurrences = when (state.recurrence?.type) {
            null -> emptyList()
            RecurrenceType.Week -> {
                state.recurrence.weekdays.map {
                    val start =
                        state.recurrence.start.plusDays(
                            (7 + it.value - state.recurrence.start.dayOfWeek.value) % 7L
                        )
                    TaskRecurrence(
                        taskId = taskId ?: 0L,
                        start = start,
                        type = state.recurrence.type,
                        step = state.recurrence.step,
                        week = start.get(weekOfMonthField()),
                    )
                }
            }

            else -> {
                listOf(
                    TaskRecurrence(
                        taskId = taskId ?: 0L,
                        start = state.recurrence.start,
                        type = state.recurrence.type,
                        step = state.recurrence.step,
                        week = state.recurrence.start.get(weekOfMonthField()),
                    )
                )
            }
        }

        if (taskId == null) {
            val form = NewTaskForm(
                name = nameInput,
                deadlineDate = deadlineDate,
                deadlineTime = state.deadlineTime,
                scheduledDate = scheduledDate,
                scheduledTime = state.scheduledTime,
                categoryId = state.category?.id,
                recurrences = recurrences,
                parentTaskId = state.parentTask?.id,
                clock = clock,
            )
            viewModelScope.launch {
                try {
                    taskRepository.create(form)
                    saveResult.value = Result.Success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create task with form: %s", form)
                    saveResult.value = Result.Failure
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    val existingTask = checkNotNull(existingTaskStream.first().getOrNull())
                    val existingCategory = existingCategoryStream.first()
                    val existingRecurrences =
                        existingRecurrencesStream.first().getOrDefault(emptyList())
                    val existingParent = existingParentTaskStream.first()
                    val hasCategories = hasCategoriesStream.first()
                    val taskCount = taskCountStream.first()
                    taskRepository.update(
                        EditTaskForm(
                            name = nameInput,
                            deadlineDate = deadlineDate,
                            deadlineTime = state.deadlineTime,
                            scheduledDate = scheduledDate,
                            scheduledTime = state.scheduledTime,
                            categoryId = if (
                                existingCategory is Result.Success &&
                                hasCategories is Result.Success
                            ) {
                                state.category?.id
                            } else {
                                existingTask.categoryId
                            },
                            recurrences = recurrences,
                            parentUpdateType = when {
                                taskCount is Result.Failure -> PathUpdateType.Keep
                                existingParent is Result.Success &&
                                        state.parentTask == existingParent.value ->
                                    PathUpdateType.Keep

                                existingParent is Result.Failure -> PathUpdateType.Keep
                                state.parentTask == null -> PathUpdateType.Remove
                                else -> PathUpdateType.Replace(state.parentTask.id)
                            },
                            existingTask = existingTask,
                            existingRecurrences = existingRecurrences,
                        )
                    )
                    saveResult.value = Result.Success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update task with id: %d", taskId)
                    saveResult.value = Result.Failure
                }
            }
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }
}
