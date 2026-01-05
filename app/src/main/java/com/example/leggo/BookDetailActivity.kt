package com.example.leggo

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

class BookDetailActivity : BaseActivity() {

    private var downloadId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_detail)

        val title = intent.getStringExtra("title")
        val author = intent.getStringExtra("author")
        val coverUrl = intent.getStringExtra("cover_url")
        val downloadUrl = intent.getStringExtra("download_url")
        val language = intent.getStringExtra("language")

        val imgCover: ImageView = findViewById(R.id.imgCover)
        val tvTitle: TextView = findViewById(R.id.tvTitle)
        val tvAuthor: TextView = findViewById(R.id.tvAuthor)
        val tvLanguage: TextView = findViewById(R.id.tvLanguage)
        val btnDownload: Button = findViewById(R.id.btnDownload)

        tvTitle.text = title
        tvAuthor.text = author ?: getString(R.string.author_unknown)
        tvLanguage.text = getString(R.string.language_label, language ?: getString(R.string.language_not_specified))

        if (coverUrl != null) {
            imgCover.load(coverUrl) { 
                crossfade(true)
                placeholder(R.drawable.leggo)
                error(R.drawable.leggo)
            }
        } else {
            imgCover.setImageResource(R.drawable.leggo)
        }

        btnDownload.setOnClickListener {
            if (downloadUrl != null) {
                downloadBook(downloadUrl, title ?: "book")
            } else {
                Toast.makeText(this, getString(R.string.no_link_available), Toast.LENGTH_SHORT).show()
            }
        }
        
        // Registra il receiver per il completamento del download
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, filter)
        }
    }
    
    private fun downloadBook(url: String, title: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription("Downloading book...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Leggo/${title}.epub")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)
            Toast.makeText(this, "Download avviato...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: apri nel browser se il DownloadManager fallisce
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.link_error), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == id) {
                Toast.makeText(context, "Download completato!", Toast.LENGTH_SHORT).show()
                
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusColumnIndex != -1) {
                        val status = cursor.getInt(statusColumnIndex)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uriColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (uriColumnIndex != -1) {
                                val uriString = cursor.getString(uriColumnIndex)
                                if (uriString != null) {
                                     val uri = Uri.parse(uriString)
                                     val titleColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                                     val title = if (titleColumnIndex != -1) {
                                         cursor.getString(titleColumnIndex)
                                     } else {
                                         "Scaricato"
                                     }
                                     
                                     // Usa lifecycleScope per chiamare la suspend function
                                     lifecycleScope.launch {
                                         BookUtils.addOrUpdateBook(context, uri, title)
                                     }
                                }
                            }
                        }
                    }
                }
                cursor.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadComplete)
    }
}
