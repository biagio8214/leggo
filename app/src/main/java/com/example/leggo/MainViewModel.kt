package com.example.leggo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.leggo.models.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _recentBooks = MutableStateFlow<List<Book>>(emptyList())
    val recentBooks: StateFlow<List<Book>> = _recentBooks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadRecentBooks() {
        viewModelScope.launch {
            val books = BookUtils.getRecentBooks(getApplication())
            _recentBooks.value = books
        }
    }

    fun scanDirectory(uri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            BookUtils.scanDirectory(getApplication(), uri)
            loadRecentBooks()
            _isLoading.value = false
            onComplete()
        }
    }

    fun renameBook(book: Book, newTitle: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = BookUtils.renameBook(getApplication(), book, newTitle)
            if (success) loadRecentBooks()
            onResult(success)
        }
    }

    fun moveToTrash(book: Book) {
        viewModelScope.launch {
            BookUtils.moveToTrash(getApplication(), book)
            loadRecentBooks()
        }
    }

    fun deleteBookFile(book: Book, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = BookUtils.deleteBookFile(getApplication(), book)
            if (success) loadRecentBooks()
            onResult(success)
        }
    }
}
