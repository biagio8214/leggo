package com.example.leggo

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import coil.load

/**
 * Gestisce la visualizzazione dei dettagli del libro e l'opzione di download.
 */
object BookDetailsDialog {

    fun show(context: Context, book: BookSearchManager.BookResult, onDownload: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_book_details, null)

        val imgCover = view.findViewById<ImageView>(R.id.book_details_cover)
        val tvTitle = view.findViewById<TextView>(R.id.book_details_title)
        val tvAuthor = view.findViewById<TextView>(R.id.book_details_authors)
        val tvFormat = view.findViewById<TextView>(R.id.book_details_format)
        val tvDesc = view.findViewById<TextView>(R.id.book_details_description)

        // Impostazione dati
        tvTitle.text = book.title
        tvAuthor.text = if (book.authors.isNotEmpty()) {
            context.getString(R.string.book_details_authors_prefix, book.authors)
        } else {
            context.getString(R.string.book_details_unknown_author)
        }

        tvFormat.text = context.getString(R.string.book_details_format_prefix, book.format?.uppercase() ?: context.getString(R.string.book_details_unknown_format))
        tvDesc.text = book.description.ifEmpty { context.getString(R.string.book_details_no_description) }

        // Caricamento copertina
        if (!book.thumbnailUrl.isNullOrEmpty()) {
            imgCover.load(book.thumbnailUrl) {
                placeholder(R.drawable.leggo)
                error(R.drawable.leggo)
            }
        } else {
            imgCover.setImageResource(R.drawable.leggo)
        }

        // Creazione Dialog con i due tasti: Scarica (Positive) e Annulla (Negative)
        AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton(R.string.download) { _, _ -> onDownload() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}