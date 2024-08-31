package io.github.evaogbe.diswantin.task.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.evaogbe.diswantin.task.data.EditTaskListForm
import io.github.evaogbe.diswantin.task.data.NewTaskListForm
import io.github.evaogbe.diswantin.task.data.Task
import io.github.evaogbe.diswantin.task.data.TaskListRepository
import io.github.evaogbe.diswantin.task.data.TaskListWithTasks
import io.github.evaogbe.diswantin.task.data.TaskRepository
import io.github.evaogbe.diswantin.ui.navigation.Destination
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class TaskListFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskListRepository: TaskListRepository,
    taskRepository: TaskRepository
) : ViewModel() {
    private val taskListId: Long? = savedStateHandle[Destination.EditTaskForm.ID_KEY]

    val isNew = taskListId == null

    var nameInput by mutableStateOf("")
        private set

    private val tasks = MutableStateFlow(persistentListOf<Task>())

    private val editingTaskIndex = MutableStateFlow<Int?>(0)

    private val taskQuery = MutableStateFlow("")

    private val saveResult = MutableStateFlow<Result<Unit>?>(null)

    private val taskListWithTasksStream = taskListId?.let { id ->
        taskListRepository.getById(id).catch { e ->
            Timber.e(e, "Failed to fetch task list by id: %d", id)
            emit(null)
        }
    } ?: flowOf(null)

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        taskListWithTasksStream,
        tasks,
        editingTaskIndex,
        taskQuery,
        taskQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                taskRepository.search(query = query, singletonsOnly = true).catch { e ->
                    Timber.e(e, "Failed to search for tasks by query: %s", query)
                    emit(emptyList())
                }
            }
        },
        saveResult,
    ) { args ->
        val existingTaskListWithTasks = args[0] as TaskListWithTasks?
        val tasks = args[1] as ImmutableList<Task>
        val editingTaskIndex = args[2] as Int?
        val taskQuery = args[3] as String
        val taskSearchResults = args[4] as List<Task>
        val saveResult = args[5] as Result<Unit>?
        when {
            saveResult?.isSuccess == true -> TaskListFormUiState.Saved
            taskListId != null && existingTaskListWithTasks == null -> TaskListFormUiState.Failure
            else -> {
                val editingTask = editingTaskIndex?.let(tasks::getOrNull)
                val taskOptions = taskSearchResults.filter { option ->
                    option !in tasks || editingTask == option
                }
                TaskListFormUiState.Success(
                    tasks = tasks,
                    editingTaskIndex = editingTaskIndex,
                    taskOptions = if (
                        taskOptions == listOf(editingTask) && taskQuery == editingTask?.name
                    ) {
                        persistentListOf()
                    } else {
                        taskOptions.toImmutableList()
                    },
                    hasSaveError = saveResult?.isFailure == true,
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TaskListFormUiState.Pending,
    )

    init {
        viewModelScope.launch {
            val taskListWithTasks = taskListWithTasksStream.first() ?: return@launch
            nameInput = taskListWithTasks.taskList.name
            tasks.value = taskListWithTasks.tasks.toPersistentList()
            editingTaskIndex.value = null
        }
    }

    fun updateNameInput(value: String) {
        nameInput = value
    }

    fun startEditTask(index: Int) {
        editingTaskIndex.value = index
    }

    fun stopEditTask() {
        editingTaskIndex.value = null
        taskQuery.value = ""
    }

    fun setTask(index: Int, task: Task) {
        tasks.update { tasks ->
            if (index < tasks.size) {
                tasks.set(index, task)
            } else {
                tasks.add(task)
            }
        }
        editingTaskIndex.value = null
        taskQuery.value = ""
    }

    fun removeTask(task: Task) {
        tasks.update { it.remove(task) }
    }

    fun searchTasks(query: String) {
        taskQuery.value = query
    }

    fun saveTaskList() {
        if (nameInput.isBlank()) return
        val state = (uiState.value as? TaskListFormUiState.Success) ?: return
        if (taskListId == null) {
            val form = NewTaskListForm(name = nameInput, tasks = state.tasks)
            viewModelScope.launch {
                try {
                    taskListRepository.create(form)
                    saveResult.value = Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create task list with params: %s", form)
                    saveResult.value = Result.failure(e)
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    val taskListWithTasks = checkNotNull(taskListWithTasksStream.first())
                    taskListRepository.update(
                        EditTaskListForm(
                            name = nameInput,
                            tasks = state.tasks,
                            taskListWithTasks = taskListWithTasks,
                        )
                    )
                    saveResult.value = Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update task list with id: %s", taskListId)
                    saveResult.value = Result.failure(e)
                }
            }
        }
    }
}
