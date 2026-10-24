package ru.mytabel.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.mytabel.app.data.*
import ru.mytabel.app.widgets.refreshWidgets

class TabelViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as TabelApplication).repository
    val state = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Snapshot())
    private val messages = Channel<String>(Channel.BUFFERED)
    val errors = messages.receiveAsFlow()
    fun perform(block: suspend Repository.() -> Unit) {
        viewModelScope.launch {
            try { repository.block(); refreshWidgets(getApplication()) }
            catch (e: Exception) { messages.send(e.message ?: "Не удалось сохранить данные") }
        }
    }
}
