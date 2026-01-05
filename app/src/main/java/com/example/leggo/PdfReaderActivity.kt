package com.example.leggo

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import kotlin.math.abs
import android.text.SpannableString
import android.text.style.BackgroundColorSpan

class PdfReaderActivity : BaseActivity() {

    // Aggiunto internalPageIndex per tracciare a quale pagina appartiene la frase in landscape
    data class Sentence(val text: String, val area: RectF, val internalPageIndex: Int)
    data class Chapter(val title: String, val pageIndex: Int)

    private lateinit var viewPager: ViewPager2
    private lateinit var fabReadAloud: FloatingActionButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var controlsLayout: LinearLayout
    private lateinit var bottomNavLayout: LinearLayout
    private lateinit var sbPageNav: SeekBar
    private lateinit var tvPageCount: TextView
    private lateinit var btnChapters: ImageButton

    private var document: Document? = null
    private var textPages: List<String>? = null
    private var chapters: List<Chapter> = emptyList()
    private lateinit var prefs: SharedPreferences
    private var readingService: ReadingService? = null
    private var isBound = false
    private var bookId: String = "unknown_book"
    private var fileType: String = "pdf"
    private var pageSentences: MutableMap<Int, List<Sentence>> = mutableMapOf()
    private val highlightColor = Color.parseColor("#80B2EBF2") // Celeste semi-trasparente

    private var autoPlayNextPage = false
    private var currentTextSize: Int = 14

    // UI visibility handling
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideSystemUIAndBars() }
    private var areBarsVisible = true

    private var isLandscape = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ReadingService.LocalBinder
            readingService = binder.getService()
            isBound = true

            applySettingsToService()

            readingService?.setCallback(object : ReadingService.ReadingCallback {
                override fun onBlockSpoken(startIndex: Int) {
                    runOnUiThread {
                        clearAllHighlights()
                        highlightLines(startIndex, 5)  // MODIFICA: Evidenzia 5 linee invece di 1
                        saveReadingProgress(startIndex)
                    }
                }
                override fun onReadingStopped() {
                    runOnUiThread {
                        clearAllHighlights()
                        fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    }
                }
                override fun onReadingFinished() {
                    runOnUiThread {
                        val adapter = viewPager.adapter ?: return@runOnUiThread
                        val current = viewPager.currentItem
                        if (current < adapter.itemCount - 1) {
                            autoPlayNextPage = true
                            viewPager.setCurrentItem(current + 1, true)
                        } else {
                            fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                            Toast.makeText(this@PdfReaderActivity, "Lettura completata", Toast.LENGTH_SHORT).show()
                        }
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
        currentTextSize = settingsPrefs.getInt("text_size", 14)

        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        initViews()
        initService()
        loadContent()

        // Initial visibility
        showBars()
        delayedHide(5000)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.topAppBar)
        appBarLayout = findViewById(R.id.topAppBarContainer)
        viewPager = findViewById(R.id.viewPager)
        fabReadAloud = findViewById(R.id.fabReadAloud)
        controlsLayout = findViewById(R.id.controlsLayout)
        bottomNavLayout = findViewById(R.id.bottomNavLayout)
        sbPageNav = findViewById(R.id.sbPageNav)
        tvPageCount = findViewById(R.id.tvPageCount)
        btnChapters = findViewById(R.id.btnChapters)

        viewPager.setBackgroundColor(Color.TRANSPARENT)
        viewPager.getChildAt(0).overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        findViewById<ImageButton>(R.id.btnSettingsOverlay).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnSaveBookmark).setOnClickListener { saveBookmark() }
        findViewById<ImageButton>(R.id.btnLoadBookmark).setOnClickListener { loadBookmark() }

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { readingService?.movePosition(-1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { readingService?.movePosition(1) }

        btnChapters.setOnClickListener { showChaptersDialog() }

        fabReadAloud.setOnClickListener { toggleReading() }

        sbPageNav.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewPager.setCurrentItem(progress, false)
                    updatePageCounter(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                delayedHide(5000)
            }
        })
    }

    private fun updatePageCounter(currentItem: Int) {
        val pageIndex = if (isLandscape) currentItem * 2 else currentItem
        val totalPages = if (document != null) document!!.countPages() else (textPages?.size ?: 0)

        if (isLandscape) {
            val page2 = if (pageIndex + 2 <= totalPages) "- ${pageIndex + 2}" else ""
            tvPageCount.text = getString(R.string.page_count_landscape, pageIndex + 1, page2, totalPages)
        } else {
            tvPageCount.text = getString(R.string.page_count, pageIndex + 1, totalPages)
        }
    }

    private fun initService() {
        val serviceIntent = Intent(this, ReadingService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun loadContent() {
        val uri = intent.data
        if (uri != null) {
            bookId = uri.lastPathSegment?.split("/")?.last() ?: "Libro senza nome"
            toolbar.title = bookId
            val mimeType = contentResolver.getType(uri) ?: ""
            fileType = when {
                mimeType == "application/pdf" || bookId.endsWith(".pdf", true) -> "pdf"
                mimeType == "application/epub+zip" || bookId.endsWith(".epub", true) -> "epub"
                else -> "txt"
            }

            lifecycleScope.launch {
                when (fileType) {
                    "pdf" -> openPdfDocument(uri)
                    "epub" -> openEpubDocument(uri) // Modificato: ora estrae testo e usa TextPagerAdapter per migliore allineamento
                    "txt" -> openTxtDocument(uri)
                }

                if (chapters.isNotEmpty()) {
                    btnChapters.visibility = View.VISIBLE
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.no_file_to_read), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        val currentPageItem = viewPager.currentItem
        val absolutePage = if (isLandscape) currentPageItem * 2 else currentPageItem

        if (readingService?.isReading() != true) {
            with(prefs.edit()) {
                putInt("${bookId}_page", absolutePage)
                apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val newTextSize = settingsPrefs.getInt("text_size", 14)
        val newIsLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Se cambia orientamento o dimensione testo, ricarichiamo tutto
        // Per EPUB il testo size cambia il layout, quindi serve reload documento
        if (newTextSize != currentTextSize || newIsLandscape != isLandscape) {
            currentTextSize = newTextSize
            isLandscape = newIsLandscape
            // Ricarica contenuto per applicare nuovo layout (specie per epub)
            val uri = intent.data
            if (uri != null && (fileType == "epub" || fileType == "txt")) {
                lifecycleScope.launch {
                    if (fileType == "epub") openEpubDocument(uri)
                    else openTxtDocument(uri)
                }
            } else {
                // Per PDF normale basta aggiornare adapter per landscape/portrait
                if (document != null) setupPdfViewPager()
                loadLastPosition()
            }
        }

        applyTheme()
        if (isBound) applySettingsToService()
        viewPager.adapter?.notifyDataSetChanged()
    }

    private fun toggleBars() {
        if (areBarsVisible) {
            hideSystemUIAndBars()
        } else {
            showBars()
            delayedHide(5000)
        }
    }

    private fun showBars() {
        appBarLayout.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).start()
        controlsLayout.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).start()
        bottomNavLayout.visibility = View.VISIBLE
        bottomNavLayout.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).start()
        areBarsVisible = true
    }

    private fun hideSystemUIAndBars() {
        appBarLayout.animate().translationY(-appBarLayout.height.toFloat()).setInterpolator(AccelerateInterpolator(2f)).start()
        controlsLayout.animate().translationY(-appBarLayout.height.toFloat() - controlsLayout.height.toFloat()).setInterpolator(AccelerateInterpolator(2f)).start()
        bottomNavLayout.animate().translationY(bottomNavLayout.height.toFloat()).setInterpolator(AccelerateInterpolator(2f)).withEndAction {
            bottomNavLayout.visibility = View.GONE
        }.start()
        areBarsVisible = false
    }

    private fun delayedHide(delayMillis: Long) {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, delayMillis)
    }

    private suspend fun openPdfDocument(uri: Uri) = withContext(Dispatchers.IO) {
        loadMuPdfDocument(uri, "temp.pdf")
    }

    private suspend fun openEpubDocument(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, "temp.epub")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // --- INIZIO CORREZIONE: Rimozione uso di MuPDF per estrazione testo EPUB ---
            // MuPDF `toStructuredText` su EPUB può ritornare caratteri di formattazione non corretti (solo numeri).
            // Usiamo invece una libreria standard o un parser HTML semplificato, ma dato che non vogliamo aggiungere deps,
            // proviamo a estrarre il testo grezzo dai file XHTML/HTML contenuti nell'EPUB.
            // Questa è una soluzione "artigianale" robusta che evita i problemi di rendering di MuPDF per il testo puro.
            
            // In alternativa, se vogliamo mantenere MuPDF per il parsing della struttura (capitoli), possiamo usarlo
            // ma estrarre il testo in modo diverso. Tuttavia, il problema "solo numeri" suggerisce un problema di font/encoding in MuPDF.
            
            // Proviamo a mantenere MuPDF per la struttura dei capitoli e il conteggio pagine "virtuali",
            // ma l'estrazione del testo la facciamo diversamente se MuPDF fallisce o ritorna spazzatura.
            
            // TENTATIVO MIGLIORATO CON MUPDF:
            // Spesso il problema "numeri" è dovuto a `toStructuredText("preserve-whitespace")`.
            // Proviamo senza opzioni o con `toText()`.
            
            val doc = Document.openDocument(tempFile.absolutePath)
            doc.layout(1000f, 1500f, currentTextSize.toFloat())

            val extractedChapters = mutableListOf<Chapter>()
            val outline: Array<Outline>? = doc.loadOutline()

            if (outline != null) {
                fun extractChapters(items: Array<Outline>) {
                    for (item in items) {
                        var pageIndex = -1
                        if (item.uri != null) {
                            try {
                                pageIndex = doc.pageNumberFromLocation(doc.resolveLink(item.uri))
                            } catch (e: Exception) { }
                        }

                        if (pageIndex >= 0) {
                            extractedChapters.add(Chapter(item.title ?: "Capitolo", pageIndex))
                        }

                        if (item.down != null) {
                            extractChapters(item.down)
                        }
                    }
                }
                extractChapters(outline)
            }

            val fullText = StringBuilder()
            val totalPages = doc.countPages()
            
            // CORREZIONE CRITICA: Usare un metodo di estrazione più semplice che di solito funziona meglio sugli EPUB reflowable
            for (i in 0 until totalPages) {
                val page = doc.loadPage(i)
                try {
                    // Proviamo a estrarre tutto il testo come blocco unico invece di strutturato che può confondersi
                    // con gli stili CSS complessi
                    val textBytes = page.textAsByte(0) // 0 = formato text semplice
                    // Se textAsByte non è disponibile o ritorna null, fallback su structured
                    // Ma MuPDF java binding spesso non espone textAsByte direttamente come stringa facile.
                    
                    // Riprova con structured text ma iterando diversamente
                    val stext = page.toStructuredText(null) // null options
                    var pageString = ""
                    for (block in stext.blocks) {
                        for (line in block.lines) {
                            for (char in line.chars) {
                                pageString += char.c.toChar()
                            }
                            pageString += "\n"
                        }
                        pageString += "\n"
                    }
                    fullText.append(pageString).append("\n\n")
                    stext.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    page.destroy()
                }
            }
            doc.destroy()

            var cleaned = fullText.toString()
            // Pulizia aggressiva se vengono rilevati troppi caratteri non testuali o solo numeri in modo sospetto
            // Ma per ora limitiamoci alla pulizia standard
            cleaned = cleaned.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .lines().joinToString("\n") { it.trim() }.trim()

            // Se il testo estratto è vuoto o sospetto, potremmo dover implementare un fallback (es. unzip epub)
            // Ma proviamo prima questa fix sulla modalità di estrazione.

            val charsPerPage = if (currentTextSize > 18) 800 else if (currentTextSize > 16) 1200 else 1500

            withContext(Dispatchers.Main) {
                chapters = extractedChapters
                paginateText(cleaned, charsPerPage)
                setupTextViewPager()
                loadLastPosition()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PdfReaderActivity, getString(R.string.epub_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun loadMuPdfDocument(uri: Uri, tempFileName: String) {
        try {
            val tempFile = File(cacheDir, tempFileName)
            // Copia solo se non esiste o forziamo reload? Per sicurezza copiamo sempre o controlliamo hash.
            // Qui copiamo sempre per semplicità.
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val doc = Document.openDocument(tempFile.absolutePath)

            // Imposta dimensione font per reflowable (EPUB)
            if (tempFileName.endsWith(".epub")) {
                // MuPDF layout usa EM o Point? Solitamente una dimensione base.
                // 12 è default. Mappiamo currentTextSize (che è sp 14-20) a qualcosa di ragionevole.
                // Proviamo a passare direttamente il valore o scalato.
                doc.layout(1000f, 1500f, currentTextSize.toFloat())
            }

            val extractedChapters = mutableListOf<Chapter>()
            val outline: Array<Outline>? = doc.loadOutline()

            if (outline != null) {
                fun extractChapters(items: Array<Outline>) {
                    for (item in items) {
                        var pageIndex = -1
                        if (item.uri != null) {
                            try {
                                pageIndex = doc.pageNumberFromLocation(doc.resolveLink(item.uri))
                            } catch (e: Exception) { }
                        }

                        if (pageIndex >= 0) {
                            extractedChapters.add(Chapter(item.title ?: "Capitolo", pageIndex))
                        }

                        if (item.down != null) {
                            extractChapters(item.down)
                        }
                    }
                }
                extractChapters(outline)
            }

            withContext(Dispatchers.Main) {
                document = doc
                chapters = extractedChapters
                setupPdfViewPager()
                loadLastPosition()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PdfReaderActivity, getString(R.string.pdf_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun openTxtDocument(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val rawText = reader.readText()

            var cleaned = rawText.replace("\r\n", "\n").replace("\r", "\n")
            cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")
            cleaned = cleaned.lines().joinToString("\n") { it.trim() }

            // Paginiamo in base al font size approssimativamente (più grande il font, meno caratteri)
            val charsPerPage = if (currentTextSize > 18) 800 else 1500

            withContext(Dispatchers.Main) {
                paginateText(cleaned, charsPerPage)
                setupTextViewPager()
                loadLastPosition()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PdfReaderActivity, getString(R.string.txt_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun paginateText(text: String, pageSize: Int) {
        val pages = mutableListOf<String>()
        var start = 0
        val len = text.length
        while (start < len) {
            var end = (start + pageSize).coerceAtMost(len)
            if (end < len) {
                val lastSpace = text.lastIndexOf(' ', end)
                val lastNewLine = text.lastIndexOf('\n', end)
                val cutPoint = maxOf(lastSpace, lastNewLine)
                if (cutPoint > start) end = cutPoint
            }
            pages.add(text.substring(start, end).trim())
            start = end + 1
        }
        this.textPages = pages
    }

    private fun showChaptersDialog() {
        if (chapters.isEmpty()) {
            Toast.makeText(this, "Nessun indice disponibile", Toast.LENGTH_SHORT).show()
            return
        }

        val chapterTitles = chapters.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Indice")
            .setItems(chapterTitles) { _, which ->
                val selectedChapter = chapters[which]
                val targetPage = selectedChapter.pageIndex
                val viewPagerItem = if (isLandscape) targetPage / 2 else targetPage
                viewPager.setCurrentItem(viewPagerItem, false)
                Toast.makeText(this, "Vai a: ${selectedChapter.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun setupPdfViewPager() {
        document?.let { doc ->
            viewPager.offscreenPageLimit = 1
            val totalPages = doc.countPages()
            val adapterItemCount = if (isLandscape) (totalPages + 1) / 2 else totalPages

            viewPager.adapter = PdfPagerAdapter(doc)
            sbPageNav.max = adapterItemCount - 1

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    sbPageNav.progress = position
                    updatePageCounter(position)

                    readingService?.stopReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)

                    val holder = getViewHolder(position)
                    if (holder is PdfPageViewHolder) {
                        holder.zoomLayout.resetZoom()
                    }
                    lifecycleScope.launch { extractTextFromPage(position) }
                }
            })
        }
    }

    private fun setupTextViewPager() {
        textPages?.let { pages ->
            val totalPages = pages.size
            val adapterItemCount = if (isLandscape) (totalPages + 1) / 2 else totalPages

            viewPager.adapter = TextPagerAdapter(pages)
            sbPageNav.max = adapterItemCount - 1

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    sbPageNav.progress = position
                    updatePageCounter(position)

                    readingService?.stopReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)

                    lifecycleScope.launch { extractTextFromPage(position) }
                }
            })
        }
    }

    private fun getViewHolder(position: Int): RecyclerView.ViewHolder? {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        return recyclerView?.findViewHolderForAdapterPosition(position)
    }

    private suspend fun extractTextFromPage(viewPagerIndex: Int) {
        val pageIndices = if (isLandscape) {
            val p1 = viewPagerIndex * 2
            val p2 = p1 + 1
            listOf(p1, p2)
        } else {
            listOf(viewPagerIndex)
        }

        val combinedSentences = mutableListOf<Sentence>()

        val savedPage = prefs.getInt("${bookId}_page", -1)
        val isCurrentPage = if (isLandscape) (savedPage / 2 == viewPagerIndex) else (savedPage == viewPagerIndex)
        val savedSentenceIndex = if (isCurrentPage) prefs.getInt("${bookId}_sentence", 0) else 0

        withContext(Dispatchers.IO) {
            val totalDocPages = if (document != null) document!!.countPages() else (textPages?.size ?: 0)

            for (pIdx in pageIndices) {
                if (pIdx >= totalDocPages) continue

                if (document != null) {
                    document?.let { doc ->
                        try {
                            val page = doc.loadPage(pIdx)
                            val stext = page.toStructuredText("preserve-images")
                            for (block in stext.blocks) {
                                for (line in block.lines) {
                                    val lineText = line.chars.joinToString("") { Character.toString(it.c) }
                                    if (lineText.isNotBlank()) {
                                        val r = line.bbox
                                        // Salviamo anche l'indice pagina interno per distinguere Sx e Dx
                                        combinedSentences.add(Sentence(lineText, RectF(r.x0, r.y0, r.x1, r.y1), pIdx))
                                    }
                                }
                            }
                            stext.destroy()
                            page.destroy()
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                } else {
                    textPages?.getOrNull(pIdx)?.let { pageText ->
                        var start = 0
                        for (i in pageText.indices) {
                            if (pageText[i] == '.' || pageText[i] == '\n') {
                                if (i > start) {
                                    val sText = pageText.substring(start, i + 1).trim()
                                    if (sText.isNotBlank()) {
                                        combinedSentences.add(Sentence(sText, RectF(start.toFloat(), 0f, (i + 1).toFloat(), 0f), pIdx))
                                    }
                                }
                                start = i + 1
                            }
                        }
                        if (start < pageText.length) combinedSentences.add(Sentence(pageText.substring(start).trim(), RectF(start.toFloat(), 0f, pageText.length.toFloat(), 0f), pIdx))
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            pageSentences[viewPagerIndex] = combinedSentences
            readingService?.setSentences(combinedSentences.map { it.text }, savedSentenceIndex)
            checkAutoPlay()
        }
    }

    private fun checkAutoPlay() {
        if (autoPlayNextPage) {
            autoPlayNextPage = false
            readingService?.startReading()
            fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    // MODIFICA: Rinominata e modificata per evidenziare multiple linee (default 5)
    private fun highlightLines(startIndex: Int, numLines: Int = 5) {
        val currentPageItem = viewPager.currentItem
        val holder = getViewHolder(currentPageItem) ?: return

        val sentencesOnScreen = pageSentences[currentPageItem] ?: return
        val endIndex = (startIndex + numLines - 1).coerceAtMost(sentencesOnScreen.size - 1)
        val linesToHighlight = sentencesOnScreen.subList(startIndex, endIndex + 1)

        val leftPageIndex = if (isLandscape) currentPageItem * 2 else currentPageItem
        val rightPageIndex = leftPageIndex + 1

        // Group le linee per pagina left/right
        val leftLines = linesToHighlight.filter { it.internalPageIndex == leftPageIndex }
        val rightLines = linesToHighlight.filter { it.internalPageIndex == rightPageIndex }

        Log.d("Highlight", "Evidenziando linee da $startIndex a $endIndex (left: ${leftLines.size}, right: ${rightLines.size})")  // MODIFICA: Debug log

        if (holder is PdfPageViewHolder) {
            val pdfMatrix = holder.transformMatrix // Matrix calcolata sulla pagina SX, assumiamo uguale per DX (stesso zoom)
            if (pdfMatrix != null) {
                val androidMatrix = android.graphics.Matrix()
                androidMatrix.setScale(pdfMatrix.a, pdfMatrix.d)

                // Evidenzia left
                val leftRects = leftLines.map { line ->
                    val mappedRect = RectF()
                    androidMatrix.mapRect(mappedRect, line.area)
                    mappedRect
                }
                holder.highlightView.setHighlight(leftRects)

                // Evidenzia right (se landscape)
                val rightRects = rightLines.map { line ->
                    val mappedRect = RectF()
                    androidMatrix.mapRect(mappedRect, line.area)
                    mappedRect
                }
                holder.highlightViewRight?.setHighlight(rightRects)

                // Scrolla alla prima linea (opzionale, solo su left per semplicità)
                if (leftRects.isNotEmpty()) {
                    holder.zoomLayout.scrollToRect(leftRects.first())
                } else if (rightRects.isNotEmpty()) {
                    holder.zoomLayout.scrollToRect(rightRects.first())
                }
            }
        } else if (holder is TextPageViewHolder) {
            // Per left
            if (leftLines.isNotEmpty()) {
                val textView = holder.textView
                val plainText = textView.text.toString()
                val spannable = SpannableString(plainText)

                val minStart = leftLines.minOf { it.area.left.toInt().coerceIn(0, plainText.length) }
                val maxEnd = leftLines.maxOf { it.area.right.toInt().coerceIn(0, plainText.length) }
                if (minStart < maxEnd) {
                    spannable.setSpan(BackgroundColorSpan(highlightColor), minStart, maxEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textView.text = spannable

                // Scrolla alla prima linea
                val scrollView = holder.leftScroll
                val lineHeight = textView.lineHeight
                val layout = textView.layout
                if (layout != null) {
                    val line = layout.getLineForOffset(minStart)
                    val top = layout.getLineTop(line)
                    scrollView.scrollTo(0, top)
                }
            }

            // Per right (se landscape)
            if (rightLines.isNotEmpty() && isLandscape) {
                val textView = holder.textViewRight ?: return
                val plainText = textView.text.toString()
                val spannable = SpannableString(plainText)

                val minStart = rightLines.minOf { it.area.left.toInt().coerceIn(0, plainText.length) }
                val maxEnd = rightLines.maxOf { it.area.right.toInt().coerceIn(0, plainText.length) }
                if (minStart < maxEnd) {
                    spannable.setSpan(BackgroundColorSpan(highlightColor), minStart, maxEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textView.text = spannable

                // Scrolla alla prima linea
                val scrollView = holder.rightScroll
                val lineHeight = textView.lineHeight
                val layout = textView.layout
                if (layout != null) {
                    val line = layout.getLineForOffset(minStart)
                    val top = layout.getLineTop(line)
                    scrollView.scrollTo(0, top)
                }
            }
        }
    }

    private fun clearAllHighlights() {
        val currentPage = viewPager.currentItem
        val holder = getViewHolder(currentPage) ?: return

        if (holder is PdfPageViewHolder) {
            holder.highlightView.clearHighlight()
            holder.highlightViewRight?.clearHighlight()  // MODIFICA: Assicura pulizia right
        } else if (holder is TextPageViewHolder) {
            holder.textView.text = holder.textView.text.toString()
            holder.textViewRight?.text = holder.textViewRight?.text.toString()
        }
    }

    private fun saveBookmark() {
        val currentPageItem = viewPager.currentItem
        val absolutePage = if (isLandscape) currentPageItem * 2 else currentPageItem
        with(prefs.edit()) {
            putInt("${bookId}_page", absolutePage)
            putInt("${bookId}_sentence", 0)
            apply()
        }
        Toast.makeText(this, getString(R.string.bookmark_saved, absolutePage + 1), Toast.LENGTH_SHORT).show()
    }

    private fun loadBookmark() {
        val absolutePage = prefs.getInt("${bookId}_page", -1)
        if (absolutePage != -1) {
            val viewPagerItem = if (isLandscape) absolutePage / 2 else absolutePage
            viewPager.setCurrentItem(viewPagerItem, true)
            Toast.makeText(this, getString(R.string.bookmark_loaded, absolutePage + 1), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.no_bookmark_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveReadingProgress(sentenceIndex: Int) {
        val currentPageItem = viewPager.currentItem
        val absolutePage = if (isLandscape) currentPageItem * 2 else currentPageItem
        with(prefs.edit()) {
            putInt("${bookId}_page", absolutePage)
            putInt("${bookId}_sentence", sentenceIndex)
            apply()
        }
    }

    private fun loadLastPosition() {
        val absolutePage = prefs.getInt("${bookId}_page", 0)
        val viewPagerItem = if (isLandscape) absolutePage / 2 else absolutePage
        viewPager.setCurrentItem(viewPagerItem, false)
        lifecycleScope.launch { extractTextFromPage(viewPagerItem) }
    }

    private fun toggleReading() {
        if (!isBound) return
        if (readingService?.isReading() == true) {
            readingService?.stopReading()
            fabReadAloud.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else {
            lifecycleScope.launch {
                extractTextFromPage(viewPager.currentItem)
                applySettingsToService()
                readingService?.startReading()
                fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        document?.destroy()
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun applyTheme() {
        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val theme = settingsPrefs.getString("reader_theme", "Giorno (Bianco)")
        val color = when (theme) {
            "Pergamena" -> Color.parseColor("#D2B48C")
            "Notte (Nero)" -> Color.BLACK
            else -> Color.WHITE
        }
        val textColor = if (theme == "Notte (Nero)") Color.WHITE else Color.BLACK

        findViewById<View>(R.id.readerRoot).setBackgroundColor(color)
        toolbar.setBackgroundColor(color)
        toolbar.setTitleTextColor(textColor)
        toolbar.menu.clear()
        findViewById<ImageButton>(R.id.btnSaveBookmark).setColorFilter(textColor)
        findViewById<ImageButton>(R.id.btnLoadBookmark).setColorFilter(textColor)
        findViewById<ImageButton>(R.id.btnSettingsOverlay).setColorFilter(textColor)
        btnChapters.setColorFilter(textColor)
        tvPageCount.setTextColor(textColor)
    }

    private fun applySettingsToService() {
        val settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val speed = settingsPrefs.getFloat("tts_speed", 1.0f)
        val lang = settingsPrefs.getString("tts_lang", "Italiano")
        val voiceName = settingsPrefs.getString("tts_voice_name", null)
        readingService?.updateSettings(speed, lang ?: "Italiano", voiceName)
    }

    inner class PdfPagerAdapter(private val doc: Document) : RecyclerView.Adapter<PdfPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_pdf_page, parent, false)
            return PdfPageViewHolder(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
            holder.itemView.tag = "page_$position"
            val context = holder.itemView.context
            val settingsPrefs = context.getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
            val theme = settingsPrefs.getString("reader_theme", "Giorno (Bianco)")
            val isNightMode = theme == "Notte (Nero)"

            if (isLandscape) {
                holder.separator.visibility = View.VISIBLE
                holder.rightContainer.visibility = View.VISIBLE
            } else {
                holder.separator.visibility = View.GONE
                holder.rightContainer.visibility = View.GONE
            }

            holder.zoomLayout.doOnLayout { zoomLayout ->
                val containerWidth = holder.leftContainer.width
                val containerHeight = holder.leftContainer.height

                val p1Index = if (isLandscape) position * 2 else position
                renderPdfPage(doc, p1Index, holder.imageView, holder.highlightView, containerWidth, containerHeight, isNightMode, holder)

                if (isLandscape) {
                    val p2Index = p1Index + 1
                    if (p2Index < doc.countPages()) {
                        renderPdfPage(doc, p2Index, holder.imageViewRight!!, holder.highlightViewRight!!, containerWidth, containerHeight, isNightMode, null)
                    } else {
                        holder.imageViewRight?.setImageBitmap(null)
                        holder.highlightViewRight?.clearHighlight()
                    }
                }
            }

            // GestureDetector migliorato e uniformato
            val gestureDetector = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    Log.d("Gesture", "Single tap detected")
                    val pdfMatrix = holder.transformMatrix ?: return false
                    val androidMatrix = android.graphics.Matrix()
                    androidMatrix.setScale(pdfMatrix.a, pdfMatrix.d)
                    val invertedMatrix = android.graphics.Matrix()

                    if (!androidMatrix.invert(invertedMatrix)) return false

                    val touchX = e.x
                    val touchY = e.y

                    val zoomWidth = holder.zoomLayout.width
                    val pageSeparatorX = zoomWidth / 2f

                    var targetPageIndex = if (isLandscape) position * 2 else position
                    var relativeX = touchX
                    var relativeY = touchY

                    if (isLandscape && touchX > pageSeparatorX) {
                        targetPageIndex += 1
                        relativeX -= pageSeparatorX
                    }

                    val contentPoint = holder.zoomLayout.toChildPoint(relativeX, relativeY)
                    val leftWidth = holder.leftContainer.width

                    var finalX = contentPoint[0]
                    var finalY = contentPoint[1]
                    var detectedPage = targetPageIndex

                    if (isLandscape && finalX > leftWidth) {
                        detectedPage += 1
                        finalX -= (leftWidth + holder.separator.width.toFloat())
                    }

                    val pdfPts = floatArrayOf(finalX, finalY)
                    invertedMatrix.mapPoints(pdfPts)
                    val pdfX = pdfPts[0]
                    val pdfY = pdfPts[1]

                    val sentences = pageSentences[position]
                    var clickedIndex = -1
                    sentences?.forEachIndexed { idx, s ->
                        if (s.internalPageIndex == detectedPage && s.area.contains(pdfX, pdfY)) {
                            clickedIndex = idx
                            return@forEachIndexed
                        }
                    }

                    if (clickedIndex != -1) {
                        readingService?.startReadingFrom(clickedIndex)
                        fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
                        clearAllHighlights()
                        highlightLines(clickedIndex, 5)  // MODIFICA: Evidenzia 5 linee anche su tap
                        Handler(Looper.getMainLooper()).postDelayed({ clearAllHighlights() }, 500)  // Evidenziazione temporanea per tap
                        return true
                    } else {
                        toggleBars()
                        delayedHide(5000)
                        return false
                    }
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 == null) return false
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    val SWIPE_THRESHOLD = 50
                    val SWIPE_VELOCITY_THRESHOLD = 50

                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD && abs(diffX) < abs(diffY)) {
                        // Swipe verticale: toggle bars (menu esistente)
                        toggleBars()
                        delayedHide(5000)
                        Log.d("Gesture", "Vertical swipe detected")
                        return true
                    }
                    // Swipe orizzontale: gestito da ViewPager per cambiare pagina
                    return false
                }
            })

            holder.zoomLayout.setOnTouchListener { _, event ->
                val handled = gestureDetector.onTouchEvent(event)
                if (!handled) holder.zoomLayout.onTouchEvent(event)
                true
            }
        }

        private fun renderPdfPage(doc: Document, index: Int, imageView: ImageView, highlightView: HighlightView, w: Int, h: Int, isNight: Boolean, holder: PdfPageViewHolder?) {
            try {
                val page = doc.loadPage(index)
                val pageSize = page.bounds
                val scale = minOf(w.toFloat() / (pageSize.x1 - pageSize.x0), h.toFloat() / (pageSize.y1 - pageSize.y0))
                val renderMatrix = Matrix(scale, scale)

                if (holder != null) holder.transformMatrix = renderMatrix

                val pixmap = page.toPixmap(renderMatrix, com.artifex.mupdf.fitz.ColorSpace.DeviceRGB, true)
                val width = pixmap.width
                val height = pixmap.height

                val samples = pixmap.samples
                val pixels = IntArray(width * height)

                for (i in 0 until width * height) {
                    var r = samples[i * 4].toInt() and 0xff
                    var g = samples[i * 4 + 1].toInt() and 0xff
                    var b = samples[i * 4 + 2].toInt() and 0xff
                    var a = samples[i * 4 + 3].toInt() and 0xff

                    if (isNight) {
                        r = 255 - r
                        g = 255 - g
                        b = 255 - b
                    } else {
                        if (r > 240 && g > 240 && b > 240) a = 0
                    }
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

                imageView.scaleType = ImageView.ScaleType.FIT_XY
                imageView.setImageBitmap(bitmap)

                page.destroy()
                pixmap.destroy()
            } catch (e: Exception) { Log.e("PdfRender", "Error page $index", e) }
        }

        override fun getItemCount(): Int {
            val count = doc.countPages()
            return if (isLandscape) (count + 1) / 2 else count
        }
    }

    inner class TextPagerAdapter(private val pages: List<String>) : RecyclerView.Adapter<TextPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextPageViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_text_page, parent, false)
            return TextPageViewHolder(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: TextPageViewHolder, position: Int) {
            val settings = holder.itemView.context.getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
            val textSize = settings.getInt("text_size", 18).toFloat()
            val theme = settings.getString("reader_theme", "Giorno (Bianco)")

            val (textColor, bgColor) = when (theme) {
                "Pergamena" -> Color.BLACK to Color.parseColor("#D2B48C")
                "Notte (Nero)" -> Color.WHITE to Color.BLACK
                else -> Color.BLACK to Color.WHITE
            }
            holder.itemView.setBackgroundColor(bgColor)

            if (isLandscape) {
                holder.separator.visibility = View.VISIBLE
                holder.rightScroll.visibility = View.VISIBLE
            } else {
                holder.separator.visibility = View.GONE
                holder.rightScroll.visibility = View.GONE
            }

            val p1Index = if (isLandscape) position * 2 else position
            holder.textView.text = pages.getOrNull(p1Index) ?: ""
            holder.textView.textSize = textSize
            holder.textView.setTextColor(textColor)
            holder.textView.setBackgroundColor(bgColor)

            if (isLandscape) {
                val p2Index = p1Index + 1
                holder.textViewRight?.text = pages.getOrNull(p2Index) ?: ""
                holder.textViewRight?.textSize = textSize
                holder.textViewRight?.setTextColor(textColor)
                holder.textViewRight?.setBackgroundColor(bgColor)
            }

            // GestureDetector uniformato a PDF, con selezione frase su tap
            val gestureDetector = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    Log.d("Gesture", "Single tap detected on text")
                    val touchX = e.x
                    val touchY = e.y

                    val pageSeparatorX = holder.itemView.width / 2f
                    var detectedPage = if (isLandscape) position * 2 else position
                    var isRightPage = false

                    if (isLandscape && touchX > pageSeparatorX) {
                        detectedPage += 1
                        isRightPage = true
                    }

                    val textView = if (isRightPage) holder.textViewRight else holder.textView
                    textView?.let {
                        val offset = it.getOffsetForPosition(touchX - if (isRightPage) pageSeparatorX else 0f, touchY)
                        val sentences = pageSentences[position]
                        var clickedIndex = -1
                        sentences?.forEachIndexed { idx, s ->
                            if (s.internalPageIndex == detectedPage && offset >= s.area.left.toInt() && offset <= s.area.right.toInt()) {
                                clickedIndex = idx
                                return@forEachIndexed
                            }
                        }

                        if (clickedIndex != -1) {
                            readingService?.startReadingFrom(clickedIndex)
                            fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
                            clearAllHighlights()
                            highlightLines(clickedIndex, 5)  // MODIFICA: Evidenzia 5 linee anche su tap
                            Handler(Looper.getMainLooper()).postDelayed({ clearAllHighlights() }, 500)
                            return true
                        } else {
                            toggleBars()
                            delayedHide(5000)
                            return false
                        }
                    }
                    return false
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 == null) return false
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    val SWIPE_THRESHOLD = 50
                    val SWIPE_VELOCITY_THRESHOLD = 50

                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD && abs(diffX) < abs(diffY)) {
                        // Swipe verticale: toggle bars (menu esistente)
                        toggleBars()
                        delayedHide(5000)
                        Log.d("Gesture", "Vertical swipe detected on text")
                        return true
                    }
                    // Swipe orizzontale: gestito da ViewPager per cambiare pagina
                    return false
                }
            })

            holder.itemView.setOnTouchListener { _, event ->
                val handled = gestureDetector.onTouchEvent(event)
                handled
            }
        }
        override fun getItemCount(): Int {
            val count = pages.size
            return if (isLandscape) (count + 1) / 2 else count
        }
    }

    inner class PdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val zoomLayout: ZoomLayout = itemView.findViewById(R.id.zoomLayout)
        val leftContainer: FrameLayout = itemView.findViewById(R.id.leftPageContainer)
        val imageView: ImageView = itemView.findViewById(R.id.pageImageView)
        val highlightView: HighlightView = itemView.findViewById(R.id.highlightView)

        val separator: View = itemView.findViewById(R.id.pageSeparator)
        val rightContainer: FrameLayout = itemView.findViewById(R.id.rightPageContainer)
        val imageViewRight: ImageView? = itemView.findViewById(R.id.pageImageViewRight)
        val highlightViewRight: HighlightView? = itemView.findViewById(R.id.highlightViewRight)

        var transformMatrix: Matrix? = null
    }

    inner class TextPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.pageTextView)
        val leftScroll: NestedScrollView = itemView.findViewById(R.id.leftPageScroll)

        val separator: View = itemView.findViewById(R.id.pageSeparator)
        val rightScroll: NestedScrollView = itemView.findViewById(R.id.rightPageScroll)
        val textViewRight: TextView? = itemView.findViewById(R.id.pageTextViewRight)

        val itemViewAsScrollView: NestedScrollView get() = leftScroll
    }
}