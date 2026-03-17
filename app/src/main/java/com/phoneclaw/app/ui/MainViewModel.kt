package com.phoneclaw.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.gateway.Gateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val prompt: String = "打开系统设置",
    val isRunning: Boolean = false,
    val lastTask: TaskSnapshot? = null,
)

class MainViewModel(
    private val gateway: Gateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun onPromptChange(value: String) {
        _uiState.update { current -> current.copy(prompt = value) }
    }

    fun submit() {
        val prompt = uiState.value.prompt.trim()
        if (prompt.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { current -> current.copy(isRunning = true) }
            val result = gateway.submitUserMessage(prompt)
            _uiState.update { current ->
                current.copy(
                    isRunning = false,
                    lastTask = result,
                )
            }
        }
    }
}

class MainViewModelFactory(
    private val gateway: Gateway,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(gateway) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

