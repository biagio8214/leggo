package com.example.leggo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class SearchBooksActivity : AppCompatActivity() {

    private lateinit var etQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var recycler: RecyclerView
    private lateinit var progressBar: ProgressBar

    // API Open Library o Gutenberg (Gutendex)
    // Usiamo Gutendex per Project Gutenberg (libri gratuiti)
    interface GutendexApi {
        @GET("books")
        fun searchBooks(@Query("search") query: String): Call<GutendexResponse>
    }
    
    data class GutendexResponse(
        val count: Int,
        val results: List<BookResult>
    )

    data class BookResult(
        val id: Int,
        val title: String,
        val authors: List<Author>,
        val formats: Map<String, String>,
        @SerializedName("download_count") val downloadCount: Int
    )

    data class Author(
        val name: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_books)

        etQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnPerformSearch)
        recycler = findViewById(R.id.recyclerSearchResults)
        progressBar = findViewById(R.id.progressBar)

        recycler.layoutManager = LinearLayoutManager(this)

        // Prendi query dall'intent se presente
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
        
        val retrofit = Retrofit.Builder()
            .baseUrl("https://gutendex.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        val api = retrofit.create(GutendexApi::class.java)
        
        api.searchBooks(query).enqueue(object : Callback<GutendexResponse> {
            override fun onResponse(call: Call<GutendexResponse>, response: Response<GutendexResponse>) {
                progressBar.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                
                if (response.isSuccessful && response.body() != null) {
                    val books = response.body()!!.results
                    recycler.adapter = BookAdapter(books)
                    if (books.isEmpty()) {
                        Toast.makeText(this@SearchBooksActivity, "Nessun risultato trovato", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@SearchBooksActivity, "Errore nella ricerca", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<GutendexResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@SearchBooksActivity, "Errore di rete: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    inner class BookAdapter(private val books: List<BookResult>) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {
        
        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgCover: ImageView = itemView.findViewById(R.id.imgBookCover)
            val tvTitle: TextView = itemView.findViewById(R.id.tvBookTitle)
            val tvAuthor: TextView = itemView.findViewById(R.id.tvBookAuthor)
            val tvDesc: TextView = itemView.findViewById(R.id.tvBookDescription)
            val btnAction: Button = itemView.findViewById(R.id.btnBookAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_search, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            
            val authors = book.authors.joinToString(", ") { it.name }
            holder.tvAuthor.text = if (authors.isNotBlank()) authors else "Autore sconosciuto"
            
            // Gutendex non fornisce una "descrizione" testuale lunga facilmente accessibile nella lista search, 
            // ma possiamo mostrare download count o subjects.
            holder.tvDesc.text = "Download: ${book.downloadCount}"
            
            // Trova copertina
            val coverUrl = book.formats["image/jpeg"]
            if (coverUrl != null) {
                holder.imgCover.load(coverUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                }
            } else {
                holder.imgCover.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            holder.btnAction.setOnClickListener {
                // Apri pagina libro per scaricare
                // Cerchiamo formato epub o pdf
                val epubUrl = book.formats["application/epub+zip"]
                val pdfUrl = book.formats["application/pdf"]
                val htmlUrl = book.formats["text/html"]
                
                val urlToOpen = epubUrl ?: pdfUrl ?: htmlUrl
                
                if (urlToOpen != null) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
                    startActivity(intent)
                } else {
                    Toast.makeText(holder.itemView.context, "Formato leggibile non disponibile direttamente", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun getItemCount() = books.size
    }
}
