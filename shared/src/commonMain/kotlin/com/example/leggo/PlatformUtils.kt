package com.example.leggo

import com.example.leggo.models.Book
import com.example.leggo.database.LeggoDatabase
import com.example.leggo.database.DatabaseModule
import com.example.leggo.database.DatabaseDriverFactory

expect object PlatformUtils {
    fun getPlatformName(): String
    fun getDatabase(factory: DatabaseDriverFactory): LeggoDatabase
}

object BookManager {
    private var database: LeggoDatabase? = null

    fun init(factory: DatabaseDriverFactory) {
        database = DatabaseModule.getDatabase(factory)
    }

    suspend fun getRecentBooks(): List<Book> {
        return database?.leggoDatabaseQueries?.selectAll { id, title, uriString, coverPath, lastOpened, isTrash ->
            Book(title, uriString, coverPath, lastOpened, isTrash == 1L)
        }?.executeAsList() ?: emptyList()
    }

    suspend fun getTrashBooks(): List<Book> {
        return database?.leggoDatabaseQueries?.selectTrash { id, title, uriString, coverPath, lastOpened, isTrash ->
            Book(title, uriString, coverPath, lastOpened, isTrash == 1L)
        }?.executeAsList() ?: emptyList()
    }

    suspend fun saveBook(book: Book) {
        val id = book.uriString.hashCode().toString()
        database?.leggoDatabaseQueries?.insertBook(
            id = id,
            title = book.title,
            uriString = book.uriString,
            coverPath = book.coverPath,
            lastOpened = book.lastOpened,
            isTrash = if (book.isTrash) 1L else 0L
        )
    }

    suspend fun updateTitle(uriString: String, newTitle: String) {
        val id = uriString.hashCode().toString()
        database?.leggoDatabaseQueries?.updateTitle(newTitle, id)
    }

    suspend fun updateTrashStatus(uriString: String, isTrash: Boolean) {
        val id = uriString.hashCode().toString()
        database?.leggoDatabaseQueries?.updateTrashStatus(if (isTrash) 1L else 0L, id)
    }

    suspend fun deleteBook(uriString: String) {
        val id = uriString.hashCode().toString()
        database?.leggoDatabaseQueries?.deleteBook(id)
    }
}
