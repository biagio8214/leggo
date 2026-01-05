package com.example.leggo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.atomic.AtomicInteger

class SearchBooksActivity : BaseActivity() {

    private lateinit var etQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var recycler: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvNoResults: TextView

    // Generic model for search results
    data class BookItem(
        val title: String,
        val authors: String,
        val coverUrl: String?,
        val downloadUrl: String?,
        val language: String?,
        val description: String? = null
    )

    // Gutendex API
    interface GutendexApi {
        @GET("books")
        fun searchBooks(@Query("search") query: String, @Query("languages") languages: String?): Call<GutendexResponse>
    }
    
    data class GutendexResponse(val count: Int, val results: List<GutendexBook>)
    data class GutendexBook(
        val id: Int, val title: String, val authors: List<GutenAuthor>,
        val languages: List<String>, val formats: Map<String, String>,
        @SerializedName("download_count") val downloadCount: Int
    )
    data class GutenAuthor(val name: String)

    // Google Books API
    interface GoogleBooksApi {
        @GET("books/v1/volumes")
        fun searchBooks(
            @Query("q") query: String,
            @Query("filter") filter: String = "free-ebooks",
            @Query("printType") printType: String = "books",
            @Query("langRestrict") langRestrict: String? = null
        ): Call<GoogleBooksResponse>
    }

    data class GoogleBooksResponse(val items: List<GoogleBook>?)
    data class GoogleBook(val volumeInfo: GoogleVolumeInfo, val accessInfo: GoogleAccessInfo)
    data class GoogleVolumeInfo(
        val title: String, val authors: List<String>?, val imageLinks: GoogleImageLinks?,
        val language: String?
    )
    data class GoogleImageLinks(val thumbnail: String?, val smallThumbnail: String?)
    data class GoogleAccessInfo(val epub: GoogleAccessLink?, val pdf: GoogleAccessLink?)
    data class GoogleAccessLink(val isAvailable: Boolean, val downloadLink: String?)


    private var bookAdapter: BookAdapter? = null
    private val allBooks = mutableListOf<BookItem>()
    private val pendingRequests = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_books)

        etQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnPerformSearch)
        recycler = findViewById(R.id.recyclerSearchResults)
        progressBar = findViewById(R.id.progressBar)
        tvNoResults = findViewById(R.id.tvNoResults)

        recycler.layoutManager = LinearLayoutManager(this)
        bookAdapter = BookAdapter(allBooks)
        recycler.adapter = bookAdapter

        val initialQuery = intent.getStringExtra("QUERY")
        if (!initialQuery.isNullOrEmpty()) {
            etQuery.setText(initialQuery)
            performSearch(initialQuery)
        }

        btnSearch.setOnClickListener {
            val query = etQuery.text.toString()
            if (query.isNotBlank()) {
                performSearch(query)
            }
        }
    }
    
    private fun performSearch(query: String) {
        progressBar.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        tvNoResults.visibility = View.GONE
        
        allBooks.clear()
        bookAdapter?.notifyDataSetChanged()
        
        val prefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val appLang = prefs.getString("app_lang", "it")
        val searchLang = if (appLang == "it") "it" else "en"

        pendingRequests.set(2) 
        searchGutendex(query, searchLang)
        searchGoogleBooks(query, searchLang)
    }
    
    private fun searchGutendex(query: String, lang: String) {
        val retrofit = Retrofit.Builder().baseUrl("https://gutendex.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(GutendexApi::class.java)
        
        api.searchBooks(query, lang).enqueue(object : Callback<GutendexResponse> {
            override fun onResponse(call: Call<GutendexResponse>, response: Response<GutendexResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val rawBooks = response.body()!!.results
                    val bookItems = rawBooks.map { book ->
                        var pdfUrl: String? = null
                        var epubUrl: String? = null
                        var htmlUrl: String? = null
                        for ((key, value) in book.formats) {
                            if (key.contains("application/pdf")) pdfUrl = value
                            if (key.contains("application/epub+zip")) epubUrl = value
                            if (key.contains("text/html")) htmlUrl = value
                        }
                        val downloadUrl = pdfUrl ?: epubUrl ?: htmlUrl
                        
                        BookItem(
                            title = book.title,
                            authors = book.authors.joinToString(", ") { it.name },
                            coverUrl = book.formats.entries.find { it.key.contains("image/jpeg") }?.value,
                            downloadUrl = downloadUrl,
                            language = book.languages.firstOrNull(),
                            description = "Download: ${book.downloadCount}"
                        )
                    }
                    addResults(bookItems)
                }
                checkProgress()
            }

            override fun onFailure(call: Call<GutendexResponse>, t: Throwable) {
                checkProgress()
            }
        })
    }

    private fun searchGoogleBooks(query: String, lang: String) {
        val retrofit = Retrofit.Builder().baseUrl("https://www.googleapis.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(GoogleBooksApi::class.java)
        
        api.searchBooks(query, langRestrict = lang).enqueue(object : Callback<GoogleBooksResponse> {
            override fun onResponse(call: Call<GoogleBooksResponse>, response: Response<GoogleBooksResponse>) {
                if (response.isSuccessful) {
                    val rawBooks = response.body()?.items ?: emptyList()
                    val bookItems = rawBooks.map { book ->
                        val info = book.volumeInfo
                        val access = book.accessInfo
                        val downloadUrl = if (access.epub?.isAvailable == true && !access.epub.downloadLink.isNullOrBlank()) {
                            access.epub.downloadLink
                        } else if (access.pdf?.isAvailable == true && !access.pdf.downloadLink.isNullOrBlank()) {
                            access.pdf.downloadLink
                        } else {
                            null
                        }
                        
                        val coverUrl = info.imageLinks?.thumbnail?.replace("http://", "https://")

                        BookItem(
                            title = info.title,
                            authors = info.authors?.joinToString(", ") ?: getString(R.string.author_unknown),
                            coverUrl = coverUrl,
                            downloadUrl = downloadUrl,
                            language = info.language,
                            description = null
                        )
                    }
                    addResults(bookItems)
                }
                checkProgress()
            }
             override fun onFailure(call: Call<GoogleBooksResponse>, t: Throwable) {
                checkProgress()
            }
        })
    }
    
    @Synchronized
    private fun addResults(newBooks: List<BookItem>) {
        if (newBooks.isNotEmpty()) {
            val startPos = allBooks.size
            allBooks.addAll(newBooks)
            bookAdapter?.notifyItemRangeInserted(startPos, newBooks.size)
        }
    }
    
    private fun checkProgress() {
        if (pendingRequests.decrementAndGet() == 0) {
            progressBar.visibility = View.GONE
            if (allBooks.isEmpty()) {
                tvNoResults.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                tvNoResults.visibility = View.GONE
                recycler.visibility = View.VISIBLE
            }
        }
    }
    
    inner class BookAdapter(private val books: List<BookItem>) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {
        
        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgCover: ImageView = itemView.findViewById(R.id.imgBookCover)
            val tvTitle: TextView = itemView.findViewById(R.id.tvBookTitle)
            val tvAuthor: TextView = itemView.findViewById(R.id.tvBookAuthor)
            val tvDesc: TextView = itemView.findViewById(R.id.tvBookDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_search, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            holder.tvAuthor.text = if (book.authors.isNotBlank()) book.authors else getString(R.string.author_unknown)
            
            if (book.description != null) {
                holder.tvDesc.text = book.description
                holder.tvDesc.visibility = View.VISIBLE
            } else {
                holder.tvDesc.visibility = View.GONE
            }
            
            if (book.coverUrl != null) {
                holder.imgCover.load(book.coverUrl) { crossfade(true).placeholder(R.drawable.leggo).error(R.drawable.leggo) }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(holder.itemView.context, BookDetailActivity::class.java).apply {
                    putExtra("title", book.title)
                    putExtra("author", book.authors)
                    putExtra("cover_url", book.coverUrl)
                    putExtra("download_url", book.downloadUrl)
                    putExtra("language", book.language?.uppercase())
                }
                holder.itemView.context.startActivity(intent)
            }
        }

        override fun getItemCount() = books.size
    }
}