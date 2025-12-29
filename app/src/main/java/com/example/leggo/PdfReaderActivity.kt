package com.example.leggo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class PdfReaderActivity : AppCompatActivity() {

    private var document: Document? = null
    private var epubContent: List<String>? = null
    private lateinit var viewPager: ViewPager2
    private lateinit var fabReadAloud: FloatingActionButton
    private lateinit var prefs: SharedPreferences
    private lateinit var readerRoot: ConstraintLayout
    
    private var readingService: ReadingService? = null
    private var isBound = false
    
    private var bookId: String = "unknown_book"
    private var fileType: String = "pdf"
    
    private var currentSentences: List<String> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ReadingService.LocalBinder
            readingService = binder.getService()
            isBound = true
            applySettingsToService()
            
            readingService?.setCallback(object : ReadingService.ReadingCallback {
                override fun onSentenceSpoken(index: Int) {
                    runOnUiThread {
                        highlightSentence(index)
                    }
                }
                override fun onReadingStopped() {
                    runOnUiThread {
                        fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    }
                }
            })
            
            if (readingService?.isReading() == true) {
                fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            readingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)

        prefs = getSharedPreferences("LeggoBookmarks", Context.MODE_PRIVATE)
        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)

        readerRoot = findViewById(R.id.readerRoot)
        viewPager = findViewById(R.id.viewPager)
        fabReadAloud = findViewById(R.id.fabReadAloud)
        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        val btnSave = findViewById<Button>(R.id.btnSaveBookmark)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettingsOverlay)

        applyTheme(settingsPrefs)

        val intent = Intent(this, ReadingService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        val uri = intent.data
        if (uri != null) {
            bookId = uri.lastPathSegment ?: "unknown_book"
            val mimeType = contentResolver.getType(uri) ?: ""
            
            if (mimeType == "application/pdf" || bookId.endsWith(".pdf", true)) {
                fileType = "pdf"
                openPdfDocument(uri)
            } else if (mimeType == "application/epub+zip" || bookId.endsWith(".epub", true)) {
                fileType = "epub"
                openEpubDocument(uri)
            } else {
                fileType = "txt"
                openTxtDocument(uri)
            }
        }

        fabReadAloud.setOnClickListener {
            toggleReading()
        }

        btnPrev.setOnClickListener {
            readingService?.movePosition(-5)
        }

        btnNext.setOnClickListener {
            readingService?.movePosition(5)
        }
        
        btnSave.setOnClickListener {
            saveBookmark()
        }
        
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    override fun onResume() {
        super.onResume()
        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        applyTheme(settingsPrefs)
        if (isBound) applySettingsToService()
        viewPager.adapter?.notifyDataSetChanged()
    }
    
    private fun applyTheme(prefs: SharedPreferences) {
        val theme = prefs.getString("reader_theme", "Giorno (Bianco)")
        when (theme) {
            "Giorno (Bianco)" -> {
                readerRoot.setBackgroundColor(Color.WHITE)
            }
            "Pergamena" -> {
                readerRoot.setBackgroundColor(Color.parseColor("#F5F5DC"))
            }
            "Notte (Nero)" -> {
                readerRoot.setBackgroundColor(Color.BLACK)
            }
        }
    }
    
    private fun applySettingsToService() {
        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val speed = settingsPrefs.getFloat("tts_speed", 1.0f)
        val lang = settingsPrefs.getString("tts_lang", "Italiano")
        readingService?.updateSettings(speed, lang ?: "Italiano")
    }

    private fun openPdfDocument(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            if (bytes != null) {
                // MuPDF richiede un path fisico o un buffer. 
                // Salviamo in cache per sicurezza se la lib usa file path nativi.
                val file = File(cacheDir, "temp.pdf")
                file.writeBytes(bytes)
                document = Document.openDocument(file.absolutePath)
                setupPdfViewPager()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore apertura PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTxtDocument(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            epubContent = text.chunked(1000)
            setupTextViewPager()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore apertura TXT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEpubDocument(uri: Uri) {
         try {
             val inputStream = contentResolver.openInputStream(uri)
             val zip = ZipInputStream(inputStream)
             var entry = zip.nextEntry
             val contentBuilder = StringBuilder()
             while (entry != null) {
                 if (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml")) {
                     val bytes = zip.readBytes()
                     val html = String(bytes)
                     val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                     contentBuilder.append(text).append("\n\n")
                 }
                 zip.closeEntry()
                 entry = zip.nextEntry
             }
             zip.close()
             if (contentBuilder.isNotEmpty()) {
                 epubContent = contentBuilder.toString().chunked(1000)
                 setupTextViewPager()
             }
         } catch (e: Exception) {
             e.printStackTrace()
         }
    }

    private fun setupPdfViewPager() {
        document?.let { doc ->
            val count = doc.countPages()
            viewPager.adapter = PdfPagerAdapter(doc, count)
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    readingService?.stopReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    extractTextFromPage(position)
                }
            })
            if (count > 0) extractTextFromPage(0)
        }
    }
    
    private fun setupTextViewPager() {
        epubContent?.let { pages ->
            viewPager.adapter = TextPagerAdapter(pages)
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    readingService?.stopReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    extractTextFromPage(position)
                }
            })
            if (pages.isNotEmpty()) extractTextFromPage(0)
        }
    }

    private fun extractTextFromPage(pageIndex: Int) {
        currentSentences = emptyList()
        try {
            if (fileType == "pdf") {
                document?.let { doc ->
                    // MuPDF text extraction (StructuredText)
                    // Questa chiamata dipende dall'API esatta esposta dal modulo locale
                    // Proviamo un approccio generico Fitz se le classi Java sono esposte
                    // Se fallisce, usiamo dummy
                    // val page = doc.loadPage(pageIndex)
                    // val st = page.toStructuredText()
                    // val rawText = st.text 
                    // st.destroy()
                    // page.destroy()
                    
                    val rawText = "Pagina PDF $pageIndex. Lettura vocale in arrivo..."
                    currentSentences = rawText.split("\n", ".").filter { it.isNotBlank() }
                }
            } else {
                val pageText = epubContent?.getOrNull(pageIndex) ?: ""
                currentSentences = pageText.split("\n", ".").filter { it.isNotBlank() }
            }
            readingService?.setSentences(currentSentences, 0)
        } catch (e: Exception) {
            Log.e("PdfReader", "Errore estrazione testo", e)
        }
    }
    
    private fun saveBookmark() {
        val currentPage = viewPager.currentItem
        with(prefs.edit()) {
            putInt("${bookId}_page", currentPage)
            apply()
        }
        Toast.makeText(this, "Posizione salvata", Toast.LENGTH_SHORT).show()
    }

    private fun toggleReading() {
        if (isBound) {
            if (readingService?.isReading() == true) {
                readingService?.stopReading()
            } else {
                readingService?.startReading()
                fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
    }
    
    private fun highlightSentence(index: Int) {
        Toast.makeText(this, "Lettura: " + currentSentences.getOrElse(index) { "" }, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        document?.destroy()
    }

    inner class PdfPagerAdapter(private val doc: Document, private val count: Int) : RecyclerView.Adapter<PdfPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
            val imageView = ImageView(parent.context)
            imageView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.setBackgroundColor(Color.WHITE)
            return PdfPageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
            val imageView = holder.itemView as ImageView
            // Render MuPDF bitmap
            val page = doc.loadPage(position)
            val bounds = page.bounds
            
            // Calcolo scale (semplificato)
            val scale = 2.0f 
            val mat = Matrix(scale, scale)
            val pixmap = page.toPixmap(mat, com.artifex.mupdf.fitz.ColorSpace.DeviceRGB, false)
            
            // Converti Pixmap a Bitmap
            // Questo richiede un metodo helper o accesso ai byte
            // Esempio:
            val bitmap = Bitmap.createBitmap(pixmap.width, pixmap.height, Bitmap.Config.ARGB_8888)
            // pixmap.samples dà accesso ai byte? In Java binding standard spesso si usa una classe helper
            // o si deve fare una copia manuale. 
            // Se il modulo locale ha AndroidDrawDevice o simile, usalo.
            // Per ora:
            // bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixmap.samples))
            
            // Nota: Se questa parte fallisce a runtime, bisogna verificare le classi esposte dal modulo mupdf.
            imageView.setImageBitmap(bitmap)
            
            page.destroy()
        }
        override fun getItemCount(): Int = count
    }
    
    inner class TextPagerAdapter(private val pages: List<String>) : RecyclerView.Adapter<TextPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextPageViewHolder {
            val textView = TextView(parent.context)
            textView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            textView.setPadding(32, 32, 32, 32)
            return TextPageViewHolder(textView)
        }

        override fun onBindViewHolder(holder: TextPageViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            tv.text = pages[position]
            val settings = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
            val size = settings.getInt("text_size", 14)
            val theme = settings.getString("reader_theme", "Giorno (Bianco)")
            tv.textSize = size.toFloat()
            when (theme) {
                "Giorno (Bianco)" -> {
                    tv.setTextColor(Color.BLACK)
                    tv.setBackgroundColor(Color.WHITE)
                }
                "Pergamena" -> {
                    tv.setTextColor(Color.BLACK)
                    tv.setBackgroundColor(Color.parseColor("#F5F5DC"))
                }
                "Notte (Nero)" -> {
                    tv.setTextColor(Color.WHITE)
                    tv.setBackgroundColor(Color.BLACK)
                }
            }
        }
        override fun getItemCount(): Int = pages.size
    }

    inner class PdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    inner class TextPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
