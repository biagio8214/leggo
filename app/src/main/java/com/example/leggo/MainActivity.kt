package com.example.leggo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerRecent: RecyclerView
    private lateinit var tvRecentLabel: TextView

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // Salva nei recenti
            val title = it.lastPathSegment ?: "Documento"
            BookUtils.addRecentBook(this, it, title)
            loadRecentBooks() // Aggiorna UI
            
            val intent = Intent(this, PdfReaderActivity::class.java)
            intent.data = it
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerRecent = findViewById(R.id.recyclerRecentBooks)
        tvRecentLabel = findViewById(R.id.tvRecentLabel)
        
        // Griglia 3 colonne
        recyclerRecent.layoutManager = GridLayoutManager(this, 3)

        // Pulsanti Top Bar
        // NOTA: btnSettings nel layout precedente era il pulsante impostazioni singolo, 
        // ora abbiamo un btnMenu nel layout activity_main.xml aggiornato
        // Assicuriamoci che l'ID sia btnMenu nel XML che ho aggiornato prima.
        // Controllo: nel passo precedente ho usato btnMenu.
        
        // btnLibrary -> Biblioteca
        findViewById<ImageButton>(R.id.btnLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        
        // Tasto + (Aggiungi Libro)
        findViewById<ImageButton>(R.id.btnAddBook).setOnClickListener {
            openFileLauncher.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain", "application/vnd.google-apps.document"))
        }
        
        // Menu 3 pallini
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }
        
        val etSearch = findViewById<EditText>(R.id.etSearchBook)
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val query = etSearch.text.toString()
            if (query.isNotBlank()) {
                val intent = Intent(this, SearchBooksActivity::class.java)
                intent.putExtra("QUERY", query)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Inserisci un titolo per cercare", Toast.LENGTH_SHORT).show()
            }
        }
        
        loadRecentBooks()
    }
    
    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "Cestino")
        popup.menu.add(0, 2, 0, "Copyright e Ringraziamenti")
        // Impostazioni (opzionale se vogliamo tenerlo qui o separato, l'utente ha chiesto di sostituire/aggiungere)
        // Se l'utente ha detto "vicino ad impostazioni", forse intendeva che i 3 pallini sono EXTRA.
        // Ma nel prompt ha detto "icona tre pallini verticali vicino ad impostazioni dove aggiungere un cestino...".
        // Per pulizia, metto Impostazioni qui dentro o lascio il tasto?
        // Il layout XML ha tolto btnSettings esplicito e messo btnMenu. Quindi metto Impostazioni nel menu.
        popup.menu.add(0, 3, 0, "Impostazioni")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, TrashActivity::class.java))
                2 -> startActivity(Intent(this, CreditsActivity::class.java))
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }
    
    override fun onResume() {
        super.onResume()
        loadRecentBooks()
    }

    private fun loadRecentBooks() {
        val books = BookUtils.getRecentBooks(this)
        if (books.isNotEmpty()) {
            recyclerRecent.visibility = View.VISIBLE
            tvRecentLabel.visibility = View.VISIBLE
            recyclerRecent.adapter = RecentBookAdapter(books)
        } else {
            recyclerRecent.visibility = View.GONE
            tvRecentLabel.visibility = View.GONE
        }
    }
    
    inner class RecentBookAdapter(private val books: List<BookUtils.RecentBook>) : RecyclerView.Adapter<RecentBookAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgCover: ImageView = itemView.findViewById(R.id.imgCover)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_cover, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            
            if (book.coverPath != null && File(book.coverPath).exists()) {
                holder.imgCover.load(File(book.coverPath)) {
                    placeholder(R.drawable.leggo)
                    error(R.drawable.leggo)
                    crossfade(true)
                }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, PdfReaderActivity::class.java)
                intent.data = Uri.parse(book.uriString)
                startActivity(intent)
            }
            
            // Long click per eliminare (spostare nel cestino)
            holder.itemView.setOnLongClickListener {
                showDeleteDialog(book)
                true
            }
        }

        override fun getItemCount() = books.size
    }
    
    private fun showDeleteDialog(book: BookUtils.RecentBook) {
        AlertDialog.Builder(this)
            .setTitle("Sposta nel Cestino")
            .setMessage("Vuoi spostare '${book.title}' nel cestino?")
            .setPositiveButton("Sposta") { _, _ ->
                BookUtils.moveToTrash(this, book)
                loadRecentBooks()
                Toast.makeText(this, "Libro spostato nel cestino", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
