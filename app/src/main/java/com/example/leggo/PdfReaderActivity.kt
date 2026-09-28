package com.example.leggo

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.coroutines.resume

data class PdfSentence(
    val text: String, 
    val charRects: List<RectF>, 
    val internalPageIndex: Int,
    val lineBbox: RectF 
)

class PdfReaderActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var sbPageNav: SeekBar
    private lateinit var tvPageCount: TextView
    private lateinit var fabReadAloud: FloatingActionButton
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var settingsPrefs: android.content.SharedPreferences
    private lateinit var topAppBarContainer: View
    private lateinit var bottomNavLayout: View
    private lateinit var gestureDetector: GestureDetector
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sideMenu: View
    private lateinit var tocRecycler: RecyclerView

    private var document: Document? = null
    private var isDualPage = false
    private var bookId: String = ""

    private var readingService: ReadingService? = null
    private var isBound = false
    private var autoPlayNextPage = false
    private var isTranslationEnabled = false
    private var targetLangCode = "it"
    private val supportedLanguages = mapOf(
        "Italiano" to "it",
        "Inglese" to "en",
        "Francese" to "fr",
        "Spagnolo" to "es",
        "Tedesco" to "de",
        "Portoghese" to "pt",
        "Russo" to "ru"
    )
    private val translatedPages = mutableMapOf<Int, String>()
    private val pageSentences = mutableMapOf<Int, List<PdfSentence>>()
    
    private var currentHighlightBlockIndex: Int = -1
    private var currentHighlightStart: Int = -1
    private var currentHighlightEnd: Int = -1

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideBars() }
    private var areBarsVisible = true

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            readingService = (service as? ReadingService.LocalBinder)?.getService()
            isBound = true
            readingService?.setCallback(object : ReadingService.ReadingCallback {
                override fun onBlockSpoken(startIndex: Int) {
                    currentHighlightBlockIndex = startIndex
                    currentHighlightStart = -1
                    currentHighlightEnd = -1
                    runOnUiThread { highlightCurrentRange() }
                }
                
                override fun onWordRangeSpoken(blockIndex: Int, start: Int, end: Int) {
                    currentHighlightBlockIndex = blockIndex
                    currentHighlightStart = start
                    currentHighlightEnd = end
                    runOnUiThread { highlightCurrentRange() }
                }

                override fun onReadingStopped() { 
                    runOnUiThread { 
                        clearAllHighlights()
                        fabReadAloud.setImageResource(android.R.drawable.ic_media_play) 
                    } 
                }
                
                override fun onReadingFinished() {
                    runOnUiThread {
                        if (viewPager.currentItem < (viewPager.adapter?.itemCount ?: 0) - 1) {
                            autoPlayNextPage = true
                            viewPager.currentItem += 1
                        } else {
                            fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }
            })
            updateTtsSettings()
        }
        override fun onServiceDisconnected(name: ComponentName?) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)

        viewPager = findViewById(R.id.viewPager)
        sbPageNav = findViewById(R.id.sbPageNav)
        tvPageCount = findViewById(R.id.tvPageCount)
        fabReadAloud = findViewById(R.id.fabReadAloud)
        topAppBarContainer = findViewById(R.id.topAppBarContainer)
        bottomNavLayout = findViewById(R.id.bottomNavLayout)
        drawerLayout = findViewById(R.id.drawerLayout)
        sideMenu = findViewById(R.id.sideMenu)
        tocRecycler = findViewById(R.id.btnChapters)
        
        prefs = getSharedPreferences("LeggoBookmarks", Context.MODE_PRIVATE)
        settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)

        isDualPage = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        val uri = intent.data ?: return
        bookId = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: "book"

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null && e2 != null && e1.y - e2.y > 80) {
                    showBars()
                    return true
                }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (areBarsVisible) hideBars() else showBars()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                handlePdfLongPress()
            }
        })

        val intentService = Intent(this, ReadingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intentService)
        } else {
            startService(intentService)
        }
        bindService(intentService, connection, Context.BIND_AUTO_CREATE)

        loadPdf(uri)
        setupButtons()
        applyTheme()
        
        topAppBarContainer.post { hideBars() }
    }

    private fun handlePdfLongPress() {
        if (isTranslationEnabled) {
            isTranslationEnabled = false
            translatedPages.clear()
            if (::viewPager.isInitialized && viewPager.adapter != null) {
                viewPager.adapter!!.notifyItemChanged(viewPager.currentItem)
            }
            updateTtsSettings()
            lifecycleScope.launch { extractTextFromPage(viewPager.currentItem) }
            Toast.makeText(this, "Traduzione Disattivata", Toast.LENGTH_SHORT).show()
        } else {
            val languages = supportedLanguages.keys.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Scegli lingua traduzione")
                .setItems(languages) { _, which ->
                    val selectedName = languages[which]
                    targetLangCode = supportedLanguages[selectedName] ?: "it"
                    isTranslationEnabled = true
                    translatedPages.clear()
                    if (::viewPager.isInitialized && viewPager.adapter != null) {
                        viewPager.adapter!!.notifyItemChanged(viewPager.currentItem)
                    }
                    updateTtsSettings()
                    lifecycleScope.launch { extractTextFromPage(viewPager.currentItem) }
                    Toast.makeText(this, "Traduzione in $selectedName attivata", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btnSettingsOverlay).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<ImageButton>(R.id.btnSaveBookmark).setOnClickListener {
            val blockIndex = readingService?.getCurrentSentenceIndex() ?: 0
            prefs.edit()
                .putInt("${bookId}_page", viewPager.currentItem)
                .putInt("${bookId}_block", blockIndex)
                .apply()
            Toast.makeText(this, "Posizione salvata!", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawers()
        }

        findViewById<ImageButton>(R.id.btnLoadBookmark).setOnClickListener {
            val savedPage = prefs.getInt("${bookId}_page", -1)
            val savedBlock = prefs.getInt("${bookId}_block", 0)
            if (savedPage != -1) {
                viewPager.setCurrentItem(savedPage, false)
                lifecycleScope.launch {
                    extractTextFromPage(savedPage)
                    startReadingWithPotentialTranslation(savedPage, savedBlock)
                }
            }
            drawerLayout.closeDrawers()
        }

        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            delayedHide()
            if (readingService?.isReading() == true) readingService?.skipForward(1)
            else viewPager.currentItem += 1
        }

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            delayedHide()
            if (readingService?.isReading() == true) readingService?.skipBackward(1)
            else viewPager.currentItem -= 1
        }

        findViewById<View>(R.id.topAppBar).setOnClickListener { drawerLayout.openDrawer(sideMenu) }
        fabReadAloud.setOnClickListener { 
            delayedHide()
            toggleReading() 
        }
    }

    private fun showBars() {
        topAppBarContainer.visibility = View.VISIBLE
        bottomNavLayout.visibility = View.VISIBLE
        topAppBarContainer.animate().translationY(0f).setDuration(300).start()
        bottomNavLayout.animate().translationY(0f).setDuration(300).start()
        areBarsVisible = true
        delayedHide()
    }

    private fun hideBars() {
        topAppBarContainer.animate().translationY(-topAppBarContainer.height.toFloat()).setDuration(300).withEndAction {
            topAppBarContainer.visibility = View.GONE
        }.start()
        bottomNavLayout.animate().translationY(bottomNavLayout.height.toFloat()).setDuration(300).withEndAction {
            bottomNavLayout.visibility = View.GONE
        }.start()
        areBarsVisible = false
    }

    private fun delayedHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 5000)
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        updateTtsSettings()
        viewPager.adapter?.notifyDataSetChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasDual = isDualPage
        isDualPage = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (wasDual != isDualPage) {
            val currentPos = viewPager.currentItem
            val targetPos = if (isDualPage) currentPos / 2 else currentPos * 2
            setupPdfViewPager()
            viewPager.setCurrentItem(targetPos, false)
        }
    }

    private fun updateTtsSettings() {
        val speed = settingsPrefs.getFloat("tts_speed", 1.0f)
        val lang = if (isTranslationEnabled) {
            supportedLanguages.entries.find { it.value == targetLangCode }?.key ?: "Italiano"
        } else {
            settingsPrefs.getString("tts_lang", "Italiano") ?: "Italiano"
        }
        val voice = if (isTranslationEnabled) null else settingsPrefs.getString("tts_voice_name", null)
        readingService?.updateSettings(speed, lang, voice)
    }

    private fun loadPdf(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "temp_pdf_reader.pdf")
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfReaderActivity, "Errore: Impossibile accedere al file PDF", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                
                val doc = Document.openDocument(tempFile.absolutePath)
                if (doc == null || doc.countPages() <= 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfReaderActivity, "Errore: Il documento PDF è vuoto o corrotto", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                document = doc
                
                // Genera la copertina del PDF dalla prima pagina
                try {
                    val page = doc.loadPage(0)
                    val bitmap = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val bbox = page.bounds
                    val scaleX = 300f / (bbox.x1 - bbox.x0)
                    val scaleY = 450f / (bbox.y1 - bbox.y0)
                    val scale = minOf(scaleX, scaleY)
                    val matrix = Matrix(scale, 0f, 0f, scale, -bbox.x0 * scale, -bbox.y0 * scale)
                    val dev = AndroidDrawDevice(bitmap, 0, 0, 0, 0, bitmap.width, bitmap.height)
                    page.run(dev, matrix, null)
                    
                    val coversDir = File(filesDir, "covers")
                    if (!coversDir.exists()) coversDir.mkdirs()
                    val coverFile = File(coversDir, "$bookId.jpg")
                    FileOutputStream(coverFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    BookUtils.updateBookCover(this@PdfReaderActivity, uri, coverFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) { setupPdfViewPager() }
            } catch (e: Exception) {
                Log.e("PdfReaderActivity", "Errore apertura PDF: ${e.message}", e)
                withContext(Dispatchers.Main) { 
                    Toast.makeText(this@PdfReaderActivity, "Errore apertura PDF: ${e.localizedMessage ?: "File non valido"}", Toast.LENGTH_LONG).show() 
                }
            }
        }
    }

    private fun setupPdfViewPager() {
        document?.let { doc ->
            viewPager.adapter = PdfPagerAdapter(doc)
            sbPageNav.max = (if (isDualPage) (doc.countPages() + 1) / 2 else doc.countPages()) - 1
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    sbPageNav.progress = position; updatePageCounter(position)
                    if (!autoPlayNextPage) {
                        readingService?.stopReading()
                        fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
                    }
                    lifecycleScope.launch { extractTextFromPage(position) }
                }
            })
        }
    }

    private fun updatePageCounter(pos: Int) {
        val total = document?.countPages() ?: 0
        val current = if (isDualPage) (pos * 2 + 1) else (pos + 1)
        tvPageCount.text = "$current / $total"
    }

    private suspend fun extractTextFromPage(viewPagerIndex: Int) {
        val pageIndices = if (isDualPage) listOf(viewPagerIndex * 2, viewPagerIndex * 2 + 1) else listOf(viewPagerIndex)
        
        if (isTranslationEnabled) {
            val combined = mutableListOf<PdfSentence>()
            for (pIdx in pageIndices) {
                if (document != null && pIdx >= document!!.countPages()) continue
                var text = translatedPages[pIdx]
                if (text == null) {
                    val original = withContext(Dispatchers.IO) { extractTextString(pIdx) }
                    if (original.isNotBlank()) {
                        text = suspendTranslate(original)
                        translatedPages[pIdx] = text
                    } else {
                        text = ""
                    }
                }
                text?.split("\n")?.forEach { line ->
                    if (line.isNotBlank()) combined.add(PdfSentence(line.trim(), emptyList(), pIdx, RectF()))
                }
            }
            withContext(Dispatchers.Main) {
                pageSentences[viewPagerIndex] = combined
                readingService?.setSentences(combined.map { it.text }, 0)
                updateTtsSettings()
                if (autoPlayNextPage) {
                    autoPlayNextPage = false
                    readingService?.startReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
                }
            }
            return
        }

        val combined = mutableListOf<PdfSentence>()
        withContext(Dispatchers.IO) {
            document?.let { doc ->
                for (pIdx in pageIndices) {
                    if (pIdx >= doc.countPages()) continue
                    val page = doc.loadPage(pIdx)
                    val stext = page.toStructuredText() 
                    val pageSentencesList = mutableListOf<PdfSentence>()
                    
                    for (block in stext.blocks) {
                        for (line in block.lines) {
                            val textBuilder = StringBuilder()
                            val charRects = mutableListOf<RectF>()
                            for (char in line.chars) {
                                textBuilder.append(char.c.toChar())
                                // MuPDF Quad ha i campi ul_x, ul_y, ur_x, ur_y, ll_x, ll_y, lr_x, lr_y
                                val q = char.quad
                                val minX = minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                                val maxX = maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                                val minY = minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                                val maxY = maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                                charRects.add(RectF(minX, minY, maxX, maxY))
                            }
                            val text = textBuilder.toString()
                            if (text.isNotBlank()) {
                                val lb = line.bbox
                                pageSentencesList.add(PdfSentence(
                                    text, 
                                    charRects, 
                                    pIdx, 
                                    RectF(lb.x0, lb.y0, lb.x1, lb.y1)
                                ))
                            }
                        }
                    }
                    combined.addAll(pageSentencesList)
                    stext.destroy(); page.destroy()
                }
            }
        }
        withContext(Dispatchers.Main) { 
            pageSentences[viewPagerIndex] = combined
            readingService?.setSentences(combined.map { it.text }, 0)
            if (autoPlayNextPage) { 
                autoPlayNextPage = false
                readingService?.startReading()
                fabReadAloud.setImageResource(android.R.drawable.ic_media_pause) 
            }
        }
    }

    private fun startReadingWithPotentialTranslation(pageIndex: Int, blockIndex: Int) {
        lifecycleScope.launch {
            extractTextFromPage(pageIndex)
            readingService?.startReading()
            readingService?.skipForward(blockIndex)
            fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private suspend fun suspendTranslate(text: String): String = suspendCancellableCoroutine { cont ->
        TranslationHelper.translate(text, null, targetLangCode, { res ->
            cont.resume(res)
        }, {
            cont.resume(text)
        })
    }

    private fun highlightCurrentRange() {
        val pos = viewPager.currentItem
        val holder = (viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? PdfPageViewHolder ?: return
        val allSentences = pageSentences[pos] ?: return
        
        if (currentHighlightBlockIndex < 0 || currentHighlightBlockIndex >= allSentences.size) return
        
        val sentence = allSentences[currentHighlightBlockIndex]
        
        holder.transformMatrix?.let { m ->
            val am = android.graphics.Matrix()
            am.setValues(floatArrayOf(m.a, m.c, m.e, m.b, m.d, m.f, 0f, 0f, 1f))
            
            val highlightedRects = mutableListOf<RectF>()
            
            if (currentHighlightStart >= 0 && currentHighlightEnd > currentHighlightStart) {
                for (i in currentHighlightStart until currentHighlightEnd.coerceAtMost(sentence.charRects.size)) {
                    val r = RectF()
                    am.mapRect(r, sentence.charRects[i])
                    highlightedRects.add(r)
                }
            } else {
                val r = RectF()
                am.mapRect(r, sentence.lineBbox)
                highlightedRects.add(r)
            }

            if (highlightedRects.isNotEmpty()) {
                val firstRect = highlightedRects.first()
                if (sentence.internalPageIndex == (if (isDualPage) pos * 2 else pos)) {
                    holder.highlightView.setHighlight(highlightedRects)
                    holder.highlightViewRight.clearHighlight()
                    if (holder.zoomLayout.isZoomed()) holder.zoomLayout.scrollToRect(firstRect)
                } else {
                    holder.highlightViewRight.setHighlight(highlightedRects)
                    holder.highlightView.clearHighlight()
                    val offsetRect = RectF(firstRect)
                    val sepWidth = if (holder.separator.width > 0) holder.separator.width else 0
                    offsetRect.offset(holder.leftContainer.width.toFloat() + sepWidth.toFloat(), 0f)
                    if (holder.zoomLayout.isZoomed()) holder.zoomLayout.scrollToRect(offsetRect)
                }
            }
        }
    }

    private fun clearAllHighlights() {
        val holder = (viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(viewPager.currentItem) as? PdfPageViewHolder ?: return
        holder.highlightView.clearHighlight()
        holder.highlightViewRight.clearHighlight()
    }

    private fun toggleReading() {
        if (readingService?.isReading() == true) readingService?.stopReading()
        else { 
            lifecycleScope.launch { 
                extractTextFromPage(viewPager.currentItem)
                startReadingWithPotentialTranslation(viewPager.currentItem, 0)
            } 
        }
    }

    private fun applyTheme() {
        val theme = settingsPrefs.getString("reader_theme", "Giorno (Bianco)")
        val bgColor = when (theme) {
            "Notte (Nero)" -> Color.BLACK
            "Pergamena" -> Color.parseColor("#F4ECD8")
            else -> Color.WHITE
        }
        findViewById<View>(R.id.readerRoot).setBackgroundColor(bgColor)
        tvPageCount.setTextColor(Color.parseColor("#FFD700"))
    }

    inner class PdfPagerAdapter(private val doc: Document) : RecyclerView.Adapter<PdfPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PdfPageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
            val theme = settingsPrefs.getString("reader_theme", "Giorno (Bianco)")
            val bgColor = when (theme) {
                "Notte (Nero)" -> Color.BLACK
                "Pergamena" -> Color.parseColor("#F4ECD8")
                else -> Color.WHITE
            }
            holder.zoomLayout.setBackgroundColor(bgColor)

            holder.zoomLayout.doOnLayout {
                val p1 = if (isDualPage) position * 2 else position
                renderPdfPage(doc, p1, holder.imageView, holder.highlightView, holder.leftContainer.width.toFloat(), holder.leftContainer.height.toFloat(), holder)
                if (isDualPage && p1 + 1 < doc.countPages()) {
                    holder.rightContainer.visibility = View.VISIBLE; holder.separator.visibility = View.VISIBLE
                    renderPdfPage(doc, p1 + 1, holder.imageViewRight, holder.highlightViewRight, holder.leftContainer.width.toFloat(), holder.leftContainer.height.toFloat(), null)
                } else { holder.rightContainer.visibility = View.GONE; holder.separator.visibility = View.GONE }
            }

            when (theme) {
                "Notte (Nero)" -> {
                    val matrix = ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    val filter = ColorMatrixColorFilter(matrix)
                    holder.imageView.colorFilter = filter
                    holder.imageViewRight.colorFilter = filter
                }
                "Pergamena" -> {
                    val matrix = ColorMatrix()
                    matrix.setSaturation(0f)
                    val sepiaMatrix = ColorMatrix()
                    sepiaMatrix.setScale(1f, 0.9f, 0.75f, 1f)
                    matrix.postConcat(sepiaMatrix)
                    val filter = ColorMatrixColorFilter(matrix)
                    holder.imageView.colorFilter = filter
                    holder.imageViewRight.colorFilter = filter
                }
                else -> {
                    holder.imageView.colorFilter = null
                    holder.imageViewRight.colorFilter = null
                }
            }

            val gd = GestureDetector(this@PdfReaderActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 != null && e2 != null && e1.y - e2.y > 80) {
                        showBars()
                        return true
                    }
                    return false
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (areBarsVisible) hideBars() else showBars()
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    handlePdfLongPress()
                }
            })
            holder.zoomLayout.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
            
            // Gestione Vista Tradotta
            handleTranslationView(holder, position)
        }
        override fun getItemCount() = if (isDualPage) (doc.countPages() + 1) / 2 else doc.countPages()
    }
    
    private fun handleTranslationView(holder: PdfPageViewHolder, position: Int) {
        val context = holder.itemView.context
        
        // Rimuovi eventuali viste di traduzione precedenti se presenti
        holder.leftContainer.findViewWithTag<View>("trans_view")?.let { holder.leftContainer.removeView(it) }
        holder.rightContainer.findViewWithTag<View>("trans_view")?.let { holder.rightContainer.removeView(it) }

        if (isTranslationEnabled) {
            // Nascondi immagini originali
            holder.imageView.visibility = View.INVISIBLE
            holder.imageViewRight.visibility = View.INVISIBLE
            
            // Crea e aggiungi vista testo tradotto per pagina sinistra
            addTranslatedTextToContainer(context, holder.leftContainer, if (isDualPage) position * 2 else position)
            
            // Se doppia pagina, fai lo stesso per destra
            if (isDualPage && (position * 2 + 1) < (document?.countPages() ?: 0)) {
                addTranslatedTextToContainer(context, holder.rightContainer, position * 2 + 1)
            }
        } else {
            // Ripristina visibilità
            holder.imageView.visibility = View.VISIBLE
            holder.imageViewRight.visibility = View.VISIBLE
        }
    }

    private fun addTranslatedTextToContainer(context: Context, container: FrameLayout, pageIndex: Int) {
        val scrollView = ScrollView(context).apply {
            tag = "trans_view"
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isFillViewport = true
            setBackgroundColor(if (settingsPrefs.getString("reader_theme", "") == "Notte (Nero)") Color.BLACK else Color.WHITE)
        }
        
        val textView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(32, 32, 32, 32)
            textSize = 18f
            setTextColor(if (settingsPrefs.getString("reader_theme", "") == "Notte (Nero)") Color.WHITE else Color.BLACK)
            text = translatedPages[pageIndex] ?: "Caricamento traduzione..."
        }
        
        scrollView.addView(textView)
        container.addView(scrollView)

        if (!translatedPages.containsKey(pageIndex)) {
            // Estrai testo e traduci
            lifecycleScope.launch(Dispatchers.IO) {
                val extractedText = extractTextString(pageIndex)
                withContext(Dispatchers.Main) {
                    if (extractedText.isBlank()) {
                        textView.text = "[Nessun testo rilevato in questa pagina]"
                    } else {
                        TranslationHelper.translate(extractedText, null, targetLangCode, { res ->
                            translatedPages[pageIndex] = res
                            textView.text = res
                        }, {
                            textView.text = "Errore traduzione."
                        })
                    }
                }
            }
        }
    }

    private fun extractTextString(pageIndex: Int): String {
        return try {
            val page = document?.loadPage(pageIndex)
            val text = page?.toStructuredText()
            val sb = StringBuilder()
            text?.blocks?.forEach { block ->
                block.lines?.forEach { line ->
                    line.chars?.forEach { char -> sb.append(char.c.toChar()) }
                    sb.append("\n")
                }
                sb.append("\n")
            }
            text?.destroy()
            page?.destroy()
            sb.toString()
        } catch (e: Exception) { "" }
    }

    private fun renderPdfPage(doc: Document, pageIdx: Int, iv: ImageView, hv: HighlightView, w: Float, h: Float, holder: PdfPageViewHolder?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val page = doc.loadPage(pageIdx)
            val bbox = page.bounds
            val scale = minOf(w / (bbox.x1 - bbox.x0), h / (bbox.y1 - bbox.y0))
            val matrix = Matrix(scale, 0f, 0f, scale, -bbox.x0 * scale, -bbox.y0 * scale)
            val bitmap = Bitmap.createBitmap(((bbox.x1 - bbox.x0) * scale).toInt(), ((bbox.y1 - bbox.y0) * scale).toInt(), Bitmap.Config.ARGB_8888)
            val dev = AndroidDrawDevice(bitmap, 0, 0, 0, 0, bitmap.width, bitmap.height); page.run(dev, matrix, null); dev.destroy()
            withContext(Dispatchers.Main) { 
                iv.setImageBitmap(bitmap)
                if (holder != null) holder.transformMatrix = matrix 
            }
        }
    }
    

    class PdfPageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val zoomLayout: com.example.leggo.ZoomLayout = v.findViewById(R.id.zoomLayout)
        val leftContainer: FrameLayout = v.findViewById(R.id.leftPageContainer); val imageView: ImageView = v.findViewById(R.id.pageImageView); val highlightView: HighlightView = v.findViewById(R.id.highlightView); val separator: View = v.findViewById(R.id.pageSeparator)
        val rightContainer: FrameLayout = v.findViewById(R.id.rightPageContainer); val imageViewRight: ImageView = v.findViewById(R.id.pageImageViewRight); val highlightViewRight: HighlightView = v.findViewById(R.id.highlightViewRight)
        var transformMatrix: Matrix? = null
    }
}
