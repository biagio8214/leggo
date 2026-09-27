package com.example.leggo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.leggo.models.Book
import com.example.leggo.database.DatabaseDriverFactory
import java.io.File
import androidx.documentfile.provider.DocumentFile

object BookUtils {
    private const val TAG = "BookUtils"
    private var isInitialized = false

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            try {
                BookManager.init(DatabaseDriverFactory(context))
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Errore inizializzazione database: ${e.message}", e)
            }
        }
    }

    suspend fun getRecentBooks(context: Context): List<Book> {
        return try {
            ensureInitialized(context)
            BookManager.getRecentBooks()
        } catch (e: Exception) {
            Log.e(TAG, "Errore recupero libri recenti: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun addOrUpdateBook(context: Context, uri: Uri, title: String) {
        try {
            ensureInitialized(context)
            val book = Book(title, uri.toString(), null, System.currentTimeMillis(), false)
            BookManager.saveBook(book)
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio libro: ${e.message}", e)
        }
    }

    suspend fun getLibraryBooks(context: Context): List<Book> {
        return try {
            ensureInitialized(context)
            BookManager.getRecentBooks()
        } catch (e: Exception) {
            Log.e(TAG, "Errore recupero libreria: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getTrashBooks(context: Context): List<Book> {
        return try {
            ensureInitialized(context)
            BookManager.getTrashBooks()
        } catch (e: Exception) {
            Log.e(TAG, "Errore recupero cestino: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun scanDirectory(context: Context, uri: Uri) {
        try {
            ensureInitialized(context)
            val root = DocumentFile.fromTreeUri(context, uri) ?: return
            scanRecursive(context, root)
        } catch (e: Exception) {
            Log.e(TAG, "Errore scansione directory: ${e.message}", e)
        }
    }

    private suspend fun scanRecursive(context: Context, directory: DocumentFile) {
        try {
            directory.listFiles().forEach { file ->
                if (file.isDirectory) {
                    scanRecursive(context, file)
                } else {
                    val name = file.name ?: return@forEach
                    val ext = name.lowercase().substringAfterLast(".", "")
                    if (ext in listOf("pdf", "epub", "mobi", "fb2", "txt")) {
                        addOrUpdateBook(context, file.uri, name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore scansione ricorsiva: ${e.message}", e)
        }
    }

    suspend fun renameBook(context: Context, book: Book, newName: String): Boolean {
        return try {
            ensureInitialized(context)
            BookManager.updateTitle(book.uriString, newName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Errore rinomina libro: ${e.message}", e)
            false
        }
    }

    suspend fun moveToTrash(context: Context, book: Book) {
        try {
            ensureInitialized(context)
            BookManager.updateTrashStatus(book.uriString, true)
        } catch (e: Exception) {
            Log.e(TAG, "Errore spostamento nel cestino: ${e.message}", e)
        }
    }

    suspend fun restoreFromTrash(context: Context, book: Book) {
        try {
            ensureInitialized(context)
            BookManager.updateTrashStatus(book.uriString, false)
        } catch (e: Exception) {
            Log.e(TAG, "Errore ripristino dal cestino: ${e.message}", e)
        }
    }

    suspend fun deletePermanently(context: Context, book: Book) {
        try {
            ensureInitialized(context)
            BookManager.deleteBook(book.uriString)
        } catch (e: Exception) {
            Log.e(TAG, "Errore eliminazione definitiva: ${e.message}", e)
        }
    }

    suspend fun deleteBookFile(context: Context, book: Book): Boolean {
        return try {
            ensureInitialized(context)
            val uri = Uri.parse(book.uriString)
            val file = DocumentFile.fromSingleUri(context, uri)
            val deleted = file?.delete() ?: false
            if (deleted) {
                deletePermanently(context, book)
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Errore eliminazione file fisico: ${e.message}", e)
            false
        }
    }
}
