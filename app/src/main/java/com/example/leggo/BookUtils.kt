package com.example.leggo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object BookUtils {

    private const val PREFS_NAME = "LeggoPrefs"
    private const val LIBRARY_KEY = "library_books"
    private const val TRASH_KEY = "trash_books"

    data class Book(
        val title: String,
        val uriString: String,
        var coverPath: String? = null,
        var lastOpenedTimestamp: Long
    )

    suspend fun addOrUpdateBook(context: Context, uri: Uri, title: String) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        val library = getLibrary(context)
        val existingBook = library.find { it.uriString == uri.toString() }

        if (existingBook != null) {
            existingBook.lastOpenedTimestamp = System.currentTimeMillis()
            if (existingBook.coverPath == null || !File(existingBook.coverPath!!).exists()) {
                existingBook.coverPath = extractAndSaveCover(context, uri, title)
            }
            library.remove(existingBook)
            library.add(0, existingBook)
        } else {
            val coverPath = extractAndSaveCover(context, uri, title)
            val newBook = Book(
                title = title,
                uriString = uri.toString(),
                coverPath = coverPath,
                lastOpenedTimestamp = System.currentTimeMillis()
            )
            library.add(0, newBook)
        }
        saveLibrary(context, library)
    }

    private suspend fun extractAndSaveCover(context: Context, uri: Uri, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val fileName = "cover_${title.hashCode()}.jpg"
            val coverFile = File(context.filesDir, fileName)

            if (coverFile.exists()) return@withContext coverFile.absolutePath

            if (mimeType.startsWith("application/pdf") || title.endsWith(".pdf", true)) {
                extractPdfCover(context, uri, coverFile)
            } else if (mimeType.startsWith("application/epub+zip") || title.endsWith(".epub", true)) {
                extractEpubCover(context, uri, coverFile)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("BookUtils", "Errore estrazione copertina: ${e.message}")
            null
        }
    }

    private suspend fun extractPdfCover(context: Context, uri: Uri, destFile: File): String? = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "temp_cover_extract.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }

            val doc = Document.openDocument(tempFile.absolutePath)
            val page = doc.loadPage(0)
            
            val bounds = page.bounds
            val scale = 300f / (bounds.x1 - bounds.x0)
            val matrix = Matrix(scale, scale)
            
            val pixmap = page.toPixmap(matrix, com.artifex.mupdf.fitz.ColorSpace.DeviceRGB, false)
            val width = pixmap.width
            val height = pixmap.height
            val samples = pixmap.samples
            
            val pixels = IntArray(width * height)
            for (i in 0 until width * height) {
                val r = samples[i * 4].toInt() and 0xff
                val g = samples[i * 4 + 1].toInt() and 0xff
                val b = samples[i * 4 + 2].toInt() and 0xff
                pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            
            FileOutputStream(destFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            pixmap.destroy()
            page.destroy()
            doc.destroy()
            tempFile.delete()
            
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractEpubCover(context: Context, uri: Uri, destFile: File): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zip = ZipInputStream(inputStream)
                var entry = zip.nextEntry
                var coverBytes: ByteArray? = null
                
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name.contains("cover") && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                        coverBytes = zip.readBytes()
                        break 
                    }
                    if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && name.contains("images/") && entry.size > 10000) {
                         if (coverBytes == null) coverBytes = zip.readBytes() 
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                zip.close()

                if (coverBytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                    FileOutputStream(destFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    return@withContext destFile.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun getLibraryBooks(context: Context): List<Book> = withContext(Dispatchers.IO) {
        getLibrary(context).sortedBy { it.title.lowercase() }
    }

    suspend fun getRecentBooks(context: Context): List<Book> = withContext(Dispatchers.IO) {
        getLibrary(context).take(9)
    }

    suspend fun updateBookCover(context: Context, uriString: String, newCoverPath: String) = withContext(Dispatchers.IO) {
        val library = getLibrary(context)
        val bookToUpdate = library.find { it.uriString == uriString }
        if (bookToUpdate != null) {
            bookToUpdate.coverPath = newCoverPath
            saveLibrary(context, library)
        }
    }

    suspend fun moveToTrash(context: Context, book: Book) = withContext(Dispatchers.IO) {
        val library = getLibrary(context)
        val trash = getTrashBooks(context)

        if (library.removeIf { it.uriString == book.uriString }) {
            trash.add(0, book)
            saveLibrary(context, library)
            saveTrash(context, trash)
        }
    }

    suspend fun getTrashBooks(context: Context): MutableList<Book> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(TRASH_KEY, null)
        if (json != null) {
            Gson().fromJson(json, object : TypeToken<MutableList<Book>>() {}.type)
        } else {
            mutableListOf()
        }
    }

    suspend fun restoreFromTrash(context: Context, book: Book) = withContext(Dispatchers.IO) {
        val trash = getTrashBooks(context)
        if (trash.removeIf { it.uriString == book.uriString }) {
            val library = getLibrary(context)
            library.add(0, book)
            saveTrash(context, trash)
            saveLibrary(context, library)
        }
    }

    suspend fun deletePermanently(context: Context, book: Book) = withContext(Dispatchers.IO) {
        val trash = getTrashBooks(context)
        if (trash.removeIf { it.uriString == book.uriString }) {
            try {
                val uri = Uri.parse(book.uriString)
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            if (book.coverPath != null) {
                File(book.coverPath!!).delete()
            }
            saveTrash(context, trash)
        }
    }

    private suspend fun getLibrary(context: Context): MutableList<Book> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(LIBRARY_KEY, null)
        if (json != null) {
            Gson().fromJson(json, object : TypeToken<MutableList<Book>>() {}.type)
        } else {
            mutableListOf()
        }
    }

    private suspend fun saveLibrary(context: Context, books: List<Book>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(LIBRARY_KEY, Gson().toJson(books)).apply()
    }

    private suspend fun saveTrash(context: Context, books: List<Book>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(TRASH_KEY, Gson().toJson(books)).apply()
    }
}