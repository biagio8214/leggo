package com.example.leggo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

class EpubReaderActivity : BaseActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var sbPageNav: SeekBar
    private lateinit var tvPageCount: TextView
    private lateinit var fabReadAloud: FloatingActionButton
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var tocRecycler: RecyclerView
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var settingsPrefs: android.content.SharedPreferences
    private lateinit var topAppBarContainer: View
    private lateinit var bottomNavLayout: View
    private lateinit var gestureDetector: GestureDetector

    private var textPages: List<String> = emptyList()
    private var htmlPages: List<String> = emptyList()
    private var chaptersList: List<Chapter> = emptyList()
    private var bookId: String = ""

    private var readingService: ReadingService? = null
    private var isBound = false
    
    private var currentTextSize: Int = 18
    private var currentTheme: String = "Giorno (Bianco)"
    private var currentBlockHighlightIndex: Int = -1

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideBars() }
    private var areBarsVisible = true
    private var isTranslationEnabled = false
    private val translatedPages = mutableMapOf<Int, String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            readingService = (service as? ReadingService.LocalBinder)?.getService()
            isBound = true
            
            updateTtsSettings()
            readingService?.setCallback(object : ReadingService.ReadingCallback {
                override fun onBlockSpoken(startIndex: Int) {
                    runOnUiThread { highlightBlock(startIndex) }
                }

                override fun onWordRangeSpoken(blockIndex: Int, start: Int, end: Int) {
                    // Implementazione vuota per soddisfare l'interfaccia
                }

                override fun onReadingStopped() {
                    runOnUiThread { 
                        fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
                        clearHighlight() 
                    }
                }
                override fun onReadingFinished() {
                    runOnUiThread {
                        if (viewPager.currentItem < textPages.size - 1) {
                            viewPager.currentItem += 1
                            viewPager.post { startReadingPage(viewPager.currentItem) }
                        } else {
                            fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName?) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub_reader)

        isTranslationEnabled = savedInstanceState?.getBoolean("is_translation_enabled", false) ?: false

        prefs = getSharedPreferences("LeggoBookmarks", Context.MODE_PRIVATE)
        settingsPrefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        
        topAppBarContainer = findViewById(R.id.topAppBarContainer)
        bottomNavLayout = findViewById(R.id.bottomNavLayout)
        
        loadSettings()
        initViews()
        setupButtons()
        
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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
        })

        intent.data?.let { uri ->
            bookId = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: "ebook"
            topAppBar.title = bookId.replace("_", " ")
            

            
            val intentService = Intent(this, ReadingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intentService)
            } else {
                startService(intentService)
            }
            bindService(intentService, connection, Context.BIND_AUTO_CREATE)
            lifecycleScope.launch { loadEpub(uri) }
        } ?: finish()
        
        topAppBarContainer.post { hideBars() }
        
        topAppBar.menu.add(0, 1, 0, if (isTranslationEnabled) "Mostra Originale" else "Leggi in Italiano").setOnMenuItemClickListener {
            isTranslationEnabled = !isTranslationEnabled
            it.title = if (isTranslationEnabled) "Mostra Originale" else "Leggi in Italiano"
            
            if (readingService?.isReading() == true) {
                readingService?.stopReading()
                fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
            }
            updateTtsSettings()
            
            // Ricarica la pagina corrente per applicare il cambio traduzione
            if (::viewPager.isInitialized && viewPager.adapter != null) {
                viewPager.adapter!!.notifyItemChanged(viewPager.currentItem)
            }
            Toast.makeText(this, if (isTranslationEnabled) "Modalità traduzione attiva" else "Testo originale ripristinato", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_translation_enabled", isTranslationEnabled)
        if (::viewPager.isInitialized) {
            outState.putInt("saved_page", viewPager.currentItem)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    private fun saveCurrentPosition() {
        if (::viewPager.isInitialized && textPages.isNotEmpty() && bookId.isNotEmpty()) {
            val currentPos = viewPager.currentItem
            prefs.edit().putInt("${bookId}_page", currentPos).apply()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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

    private fun loadSettings() {
        currentTextSize = settingsPrefs.getInt("text_size", 18)
        currentTheme = settingsPrefs.getString("reader_theme", "Giorno (Bianco)") ?: "Giorno (Bianco)"
    }

    override fun onResume() {
        super.onResume()
        val oldSize = currentTextSize
        val oldTheme = currentTheme
        updateTtsSettings()
        loadSettings()
        
        if (oldSize != currentTextSize || oldTheme != currentTheme) {
            viewPager.adapter?.notifyDataSetChanged()
        }
    }

    private fun updateTtsSettings() {
        val speed = settingsPrefs.getFloat("tts_speed", 1.0f)
        val lang = if (isTranslationEnabled) "Italiano" else settingsPrefs.getString("tts_lang", "Italiano") ?: "Italiano"
        val voice = if (isTranslationEnabled) null else settingsPrefs.getString("tts_voice_name", null)
        readingService?.updateSettings(speed, lang, voice)
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        viewPager = findViewById(R.id.viewPager)
        sbPageNav = findViewById(R.id.sbPageNav)
        tvPageCount = findViewById(R.id.tvPageCount)
        fabReadAloud = findViewById(R.id.fabReadAloud)
        topAppBar = findViewById(R.id.topAppBar)
        tocRecycler = findViewById(R.id.tocRecycler)

        topAppBar.setNavigationOnClickListener { drawerLayout.openDrawer(findViewById(R.id.sideMenu)) }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { 
                updateUI(position)
                if (readingService?.isReading() == true) {
                    startReadingPage(position)
                }
            }
        })

        sbPageNav.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) viewPager.setCurrentItem(p, false) }
            override fun onStartTrackingTouch(s: SeekBar?) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(s: SeekBar?) { delayedHide() }
        })
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btnNext).setOnClickListener { 
            delayedHide()
            if (readingService?.isReading() == true) readingService?.skipForward(1)
            else viewPager.currentItem += 1 
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener { 
            delayedHide()
            if (readingService?.isReading() == true) readingService?.skipBackward(1)
            else viewPager.currentItem -= 1 
        }
        
        findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnSavePosition)?.setOnClickListener {
            val blockIndex = readingService?.getCurrentSentenceIndex() ?: 0
            prefs.edit()
                .putInt("${bookId}_page", viewPager.currentItem)
                .putInt("${bookId}_block", blockIndex)
                .apply()
            Toast.makeText(this, "Posizione salvata", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnLoadPosition)?.setOnClickListener {
            val savedPage = prefs.getInt("${bookId}_page", 0)
            val savedBlock = prefs.getInt("${bookId}_block", 0)
            if (savedPage < textPages.size) {
                viewPager.setCurrentItem(savedPage, false)
                viewPager.post { startReadingPage(savedPage, savedBlock * 3) }
            } else {
                Toast.makeText(this, "Nessuna posizione salvata", Toast.LENGTH_SHORT).show()
            }
            drawerLayout.closeDrawers()
        }

        fabReadAloud.setOnClickListener {
            delayedHide()
            if (readingService?.isReading() == true) {
                readingService?.stopReading()
            } else {
                startReadingPage(viewPager.currentItem)
            }
        }
    }

    private fun startReadingPage(pageIndex: Int, sentenceIndex: Int = 0) {
        if (isTranslationEnabled && translatedPages[pageIndex] == null) {
            val originalText = textPages.getOrNull(pageIndex)
            if (originalText != null) {
                Toast.makeText(this, "Traduzione in corso...", Toast.LENGTH_SHORT).show()
                TranslationHelper.translate(originalText, null, "it", { translated ->
                    translatedPages[pageIndex] = translated
                    viewPager.adapter?.notifyItemChanged(pageIndex)
                    startReadingPage(pageIndex, sentenceIndex)
                }, {
                    Toast.makeText(this, "Errore traduzione", Toast.LENGTH_SHORT).show()
                    readingService?.stopReading()
                    fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
                })
                return
            }
        }

        val text = if (isTranslationEnabled) translatedPages[pageIndex] else textPages.getOrNull(pageIndex)

        if (text == null) {
            readingService?.stopReading()
            fabReadAloud.setImageResource(android.R.drawable.ic_media_play)
            return
        }

        val allSentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        
        val chunkSize = 3
        val chunks = allSentences.chunked(chunkSize).map { it.joinToString(" ") }
        val startBlockIndex = sentenceIndex / chunkSize
        
        if (startBlockIndex < chunks.size) {
            currentBlockHighlightIndex = startBlockIndex
            readingService?.setSentences(chunks.subList(startBlockIndex, chunks.size), 0)
            readingService?.startReading()
            fabReadAloud.setImageResource(android.R.drawable.ic_media_pause)
            highlightBlock(0)
        }
    }

    private fun highlightBlock(serviceBlockIndex: Int) {
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(viewPager.currentItem) as? PagerAdapter.ViewHolder ?: return
        
        val text = if (isTranslationEnabled) {
            translatedPages[viewPager.currentItem]
        } else {
            textPages.getOrNull(viewPager.currentItem)
        }
        
        if (text == null) return

        val realBlockIndex = if (currentBlockHighlightIndex != -1) currentBlockHighlightIndex + serviceBlockIndex else serviceBlockIndex
        
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val spannable = getSpannableText(text, viewPager.currentItem)
        
        val sentencesPerBlock = 3
        val startSentenceIdx = realBlockIndex * sentencesPerBlock
        val endSentenceIdx = (startSentenceIdx + sentencesPerBlock).coerceAtMost(sentences.size)
        
        if (startSentenceIdx < sentences.size) {
            var currentPos = 0
            var spanStart = -1
            var spanEnd = -1

            for (i in 0 until sentences.size) {
                val sentence = sentences[i]
                if (i == startSentenceIdx) {
                    spanStart = currentPos
                }
                currentPos += sentence.length + 1
                if (i == endSentenceIdx - 1) {
                    spanEnd = currentPos - 1
                }
            }

            if (spanStart != -1 && spanEnd != -1) {
                spannable.setSpan(BackgroundColorSpan(Color.parseColor("#8800CCFF")), spanStart, spanEnd.coerceAtMost(spannable.length), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        holder.tv.text = spannable
    }

    private fun clearHighlight() {
        currentBlockHighlightIndex = -1
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(viewPager.currentItem) as? PagerAdapter.ViewHolder ?: return
        
        val text = if (isTranslationEnabled) {
            translatedPages[viewPager.currentItem]
        } else {
            textPages.getOrNull(viewPager.currentItem)
        }
        
        if (text == null) return
        holder.tv.text = getSpannableText(text, viewPager.currentItem)
    }

    private fun getSpannableText(text: String, pageIndex: Int): SpannableStringBuilder {
        val spannable = SpannableStringBuilder()
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        for (i in 0 until sentences.size) {
            val sentence = sentences[i]
            val sentenceWithSpace = "$sentence "
            val start = spannable.length
            spannable.append(sentenceWithSpace)
            val end = start + sentence.length

            val sentenceIndex = i
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startReadingPage(pageIndex, sentenceIndex)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = if (currentTheme == "Notte (Nero)") Color.WHITE else Color.BLACK
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private suspend fun loadEpub(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, "temp_reader.epub")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }

            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(tempFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bos = ByteArrayOutputStream()
                        zis.copyTo(bos)
                        entries[entry.name.replace("\\", "/")] = bos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }

            var opfPath = "content.opf"
            val containerBytes = entries["META-INF/container.xml"] ?: entries["meta-inf/container.xml"]
            if (containerBytes != null) {
                val containerDoc = Jsoup.parse(String(containerBytes, Charsets.UTF_8))
                containerDoc.select("rootfile").first()?.attr("full-path")?.let { opfPath = it }
            } else {
                entries.keys.find { it.endsWith(".opf", true) }?.let { opfPath = it }
            }

            val opfBytes = entries[opfPath]
            val opfDir = opfPath.substringBeforeLast("/", "")

            val textPagesList = mutableListOf<String>()
            val htmlPagesList = mutableListOf<String>()
            val chapters = mutableListOf<Chapter>()
            var coverBytes: ByteArray? = null

            val imagesDir = File(cacheDir, "epub_images/$bookId")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val imageMap = mutableMapOf<String, String>()
            entries.forEach { (path, bytes) ->
                val lower = path.lowercase()
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                    val imgFile = File(imagesDir, path.substringAfterLast("/"))
                    FileOutputStream(imgFile).use { it.write(bytes) }
                    imageMap[path] = imgFile.absolutePath
                    imageMap[path.substringAfterLast("/")] = imgFile.absolutePath
                }
            }

            val spineHrefs = mutableListOf<String>()
            val manifestItems = mutableMapOf<String, String>()

            if (opfBytes != null) {
                val opfDoc = Jsoup.parse(String(opfBytes, Charsets.UTF_8))
                var coverItemId = opfDoc.select("meta[name=cover]").attr("content")
                if (coverItemId.isBlank()) {
                    coverItemId = opfDoc.select("item[properties~=cover-image]").attr("id")
                }

                opfDoc.select("manifest > item").forEach { item ->
                    val id = item.attr("id")
                    val href = item.attr("href")
                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                    manifestItems[id] = fullHref

                    if (id == coverItemId || item.attr("properties").contains("cover-image")) {
                        coverBytes = entries[fullHref] ?: entries[href]
                    }
                }

                opfDoc.select("spine > itemref").forEach { itemref ->
                    val idref = itemref.attr("idref")
                    manifestItems[idref]?.let { href -> spineHrefs.add(href) }
                }
            }

            if (coverBytes == null) {
                val coverEntry = entries.entries.find { (k, _) ->
                    val l = k.lowercase()
                    (l.contains("cover") || l.contains("jacket") || l.contains("titlepage")) && 
                    (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png"))
                }
                coverBytes = coverEntry?.value
            }

            if (coverBytes != null) {
                val coversDir = File(filesDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()
                val coverFile = File(coversDir, "$bookId.jpg")
                FileOutputStream(coverFile).use { it.write(coverBytes) }
                BookUtils.updateBookCover(this@EpubReaderActivity, uri, coverFile.absolutePath)
            }

            val chapterFiles = if (spineHrefs.isNotEmpty()) {
                spineHrefs
            } else {
                entries.keys.filter { it.endsWith(".html", true) || it.endsWith(".xhtml", true) || it.endsWith(".htm", true) }.sorted()
            }

            for (href in chapterFiles) {
                val bytes = entries[href] ?: entries[href.substringAfterLast("/")] ?: continue
                val htmlStr = String(bytes, Charsets.UTF_8)
                val doc = Jsoup.parse(htmlStr)
                
                doc.select("script, style, head, meta").remove()

                doc.select("img").forEach { img ->
                    val src = img.attr("src")
                    val resolvedPath = if (opfDir.isNotEmpty() && !src.startsWith("http")) {
                        "$opfDir/$src"
                    } else {
                        src
                    }
                    val localPath = imageMap[resolvedPath] ?: imageMap[src.substringAfterLast("/")]
                    if (localPath != null) {
                        img.attr("src", "file://$localPath")
                    }
                }

                val title = doc.select("h1, h2, h3").firstOrNull()?.text() ?: "Capitolo ${chapters.size + 1}"
                
                doc.select("h1").attr("style", "font-size: 1.5em; font-weight: bold; display: block; margin-top: 1em; margin-bottom: 0.5em;")
                doc.select("h2").attr("style", "font-size: 1.3em; font-weight: bold; display: block; margin-top: 0.8em; margin-bottom: 0.4em;")
                doc.select("h3").attr("style", "font-size: 1.1em; font-weight: bold; display: block; margin-top: 0.6em; margin-bottom: 0.3em;")
                doc.select("p").attr("style", "display: block; margin-bottom: 1em;")

                val plainText = doc.text().trim()
                val bodyHtml = doc.body().html().trim()

                if (plainText.isNotBlank()) {
                    chapters.add(Chapter(title, textPagesList.size))
                    
                    val chunkSize = 2500
                    if (plainText.length <= chunkSize) {
                        textPagesList.add(plainText)
                        htmlPagesList.add(bodyHtml)
                    } else {
                        var start = 0
                        while (start < plainText.length) {
                            val end = (start + chunkSize).coerceAtMost(plainText.length)
                            textPagesList.add(plainText.substring(start, end))
                            htmlPagesList.add(bodyHtml)
                            start = end
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (textPagesList.isNotEmpty()) {
                    textPages = textPagesList
                    htmlPages = htmlPagesList
                    chaptersList = chapters
                    viewPager.adapter = PagerAdapter()
                    setupToc()

                    val savedPage = prefs.getInt("${bookId}_page", 0)
                    val targetPage = savedPage.coerceIn(0, textPages.size - 1)
                    viewPager.setCurrentItem(targetPage, false)
                    updateUI(targetPage)
                } else {
                    Toast.makeText(this@EpubReaderActivity, "Errore: Libro vuoto o formato non valido", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(this@EpubReaderActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun setupToc() {
        tocRecycler.layoutManager = LinearLayoutManager(this)
        tocRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class Holder(v: View) : RecyclerView.ViewHolder(v) { val tv: TextView = v.findViewById(android.R.id.text1) }
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = Holder(LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_1, p, false))
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
                val item = h as Holder
                item.tv.text = chaptersList[p].title
                item.itemView.setOnClickListener {
                    viewPager.setCurrentItem(chaptersList[p].startIndex, false)
                    drawerLayout.closeDrawers()
                }
            }
            override fun getItemCount() = chaptersList.size
        }
    }

    private fun updateUI(position: Int) {
        if (textPages.isNotEmpty()) {
            tvPageCount.text = "${position + 1} / ${textPages.size}"
            sbPageNav.max = (textPages.size - 1).coerceAtLeast(0)
            sbPageNav.progress = position
        }
    }

    inner class PagerAdapter : RecyclerView.Adapter<PagerAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val tv: TextView = v.findViewById(R.id.pageTextView) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(
            LayoutInflater.from(p.context).inflate(R.layout.item_text_page, p, false)
        )
        override fun onBindViewHolder(h: ViewHolder, p: Int) { 
            applyTheme(h.itemView, h.tv)

            h.tv.textSize = currentTextSize.toFloat()
            h.tv.setLineSpacing(0f, 1.2f)
            h.tv.movementMethod = LinkMovementMethod.getInstance()
            h.tv.visibility = View.VISIBLE

            val htmlContent = if (isTranslationEnabled) translatedPages[p] ?: htmlPages.getOrNull(p) ?: textPages[p] else htmlPages.getOrNull(p) ?: textPages[p]
            
            val imageGetter = Html.ImageGetter { source ->
                try {
                    val cleanSource = source.removePrefix("file://")
                    val file = File(cleanSource)
                    if (file.exists()) {
                        val d = Drawable.createFromPath(file.absolutePath)
                        d?.let {
                            val maxWidth = h.tv.width.takeIf { w -> w > 0 } ?: 800
                            val scale = if (it.intrinsicWidth > maxWidth) maxWidth.toFloat() / it.intrinsicWidth else 1f
                            val w = (it.intrinsicWidth * scale).toInt()
                            val hVal = (it.intrinsicHeight * scale).toInt()
                            it.setBounds(0, 0, w, hVal)
                            return@ImageGetter it
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                null
            }

            h.tv.text = Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)

            if (isTranslationEnabled && translatedPages[p] == null) {
                h.tv.text = "Traduzione in corso..."
                val originalText = textPages[p]
                TranslationHelper.translate(originalText, null, "it", { translated ->
                    translatedPages[p] = translated
                    if (h.adapterPosition == p) {
                        h.tv.text = android.text.Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)
                    }
                }, {
                    h.tv.text = "Errore traduzione. Riprova.\n\n${textPages[p]}"
                })
            }
        }
        override fun getItemCount() = textPages.size

        private fun applyTheme(root: View, tv: TextView) {
            when (currentTheme) {
                "Notte (Nero)" -> {
                    root.setBackgroundColor(Color.BLACK)
                    tv.setTextColor(Color.WHITE)
                }
                "Pergamena" -> {
                    root.setBackgroundColor(Color.parseColor("#F4ECD8"))
                    tv.setTextColor(Color.BLACK)
                }
                else -> {
                    root.setBackgroundColor(Color.WHITE)
                    tv.setTextColor(Color.BLACK)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
    }

    data class Chapter(val title: String, val startIndex: Int)
}
