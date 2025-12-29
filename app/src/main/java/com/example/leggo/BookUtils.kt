package com.example.leggo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

object BookUtils {

    data class RecentBook(
        val title: String,
        val uriString: String,
        val path: String?,
        val coverPath: String?
    )

    private const val PREFS_NAME = "LeggoRecent"
    private const val KEY_RECENT_BOOKS = "recent_books"
    private const val KEY_TRASH_BOOKS = "trash_books"

    fun addRecentBook(context: Context, uri: Uri, title: String) {
        val list = loadList(context, KEY_RECENT_BOOKS)
        
        list.removeAll { it.uriString == uri.toString() }
        
        val coverFile = File(context.cacheDir, "cover_${uri.lastPathSegment?.hashCode()}.png")
        if (!coverFile.exists()) {
             tryGenerateCover(context, uri, coverFile)
        }

        list.add(0, RecentBook(title, uri.toString(), uri.path, coverFile.absolutePath))
        
        if (list.size > 6) {
            list.removeAt(list.size - 1)
        }

        saveList(context, KEY_RECENT_BOOKS, list)
    }

    fun getRecentBooks(context: Context): List<RecentBook> {
        return loadList(context, KEY_RECENT_BOOKS)
    }
    
    fun moveToTrash(context: Context, book: RecentBook) {
        // Rimuovi da recenti
        val recent = loadList(context, KEY_RECENT_BOOKS)
        recent.removeAll { it.uriString == book.uriString }
        saveList(context, KEY_RECENT_BOOKS, recent)
        
        // Aggiungi a cestino
        val trash = loadList(context, KEY_TRASH_BOOKS)
        if (trash.none { it.uriString == book.uriString }) {
            trash.add(0, book)
        }
        saveList(context, KEY_TRASH_BOOKS, trash)
    }
    
    fun getTrashBooks(context: Context): List<RecentBook> {
        return loadList(context, KEY_TRASH_BOOKS)
    }
    
    fun restoreFromTrash(context: Context, book: RecentBook) {
        // Rimuovi da cestino
        val trash = loadList(context, KEY_TRASH_BOOKS)
        trash.removeAll { it.uriString == book.uriString }
        saveList(context, KEY_TRASH_BOOKS, trash)
        
        // Aggiungi a recenti
        val recent = loadList(context, KEY_RECENT_BOOKS)
        if (recent.none { it.uriString == book.uriString }) {
            recent.add(0, book)
        }
        saveList(context, KEY_RECENT_BOOKS, recent)
    }
    
    fun deletePermanently(context: Context, book: RecentBook) {
        val trash = loadList(context, KEY_TRASH_BOOKS)
        trash.removeAll { it.uriString == book.uriString }
        saveList(context, KEY_TRASH_BOOKS, trash)
        
        // Potremmo cancellare anche la cache cover qui
    }

    private fun loadList(context: Context, key: String): MutableList<RecentBook> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(key, "[]")
        val type = object : TypeToken<MutableList<RecentBook>>() {}.type
        return Gson().fromJson(json, type) ?: mutableListOf()
    }
    
    private fun saveList(context: Context, key: String, list: List<RecentBook>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, Gson().toJson(list)).apply()
    }

    private fun tryGenerateCover(context: Context, uri: Uri, outFile: File) {
        try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType == "application/pdf") {
                val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                if (fileDescriptor != null) {
                    val renderer = PdfRenderer(fileDescriptor)
                    if (renderer.pageCount > 0) {
                        val page = renderer.openPage(0)
                        val bitmap = Bitmap.createBitmap(page.width / 4, page.height / 4, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        renderer.close()
                        
                        FileOutputStream(outFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                        }
                    }
                    fileDescriptor.close()
                }
            }
        } catch (e: Exception) {
            Log.e("BookUtils", "Errore generazione cover", e)
        }
    }
}
