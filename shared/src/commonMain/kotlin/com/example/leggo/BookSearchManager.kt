package com.example.leggo

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Gestisce la ricerca di libri tramite diverse fonti:
 * - Google Books (Anteprime/Gratuiti)
 * - Project Gutenberg (Pubblico Dominio)
 * - Internet Archive (Pubblico Dominio)
 * - Library Genesis (LibGen - Shadow Library)
 * Versione KMP: Usa Ktor e Kotlinx Serialization per compatibilità iOS.
 */
object BookSearchManager {

    private const val TAG = "BookSearchManager"

    // Endpoints
    private const val GOOGLE_BOOKS_URL = "https://www.googleapis.com/books/v1/volumes?filter=free-ebooks&printType=books&projection=lite"
    private const val GUTENBERG_URL = "https://gutendex.com/books"
    private const val INTERNET_ARCHIVE_URL = "https://archive.org/advancedsearch.php"

    // Lista estesa dei formati che l'app intende supportare
    val SUPPORTED_FORMATS = listOf(
        "pdf", "epub", "mobi", "azw3", "azw", "fb2", "fb3", "djvu",
        "doc", "docx", "rtf", "odt", "txt", "cbr", "cbz", "chm"
    )

    // Client HTTP configurato per KMP
    // Nota: L'engine (CIO, Darwin) viene iniettato automaticamente dalle dipendenze del modulo
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
        }
    }

    data class BookResult(
        val id: String,
        val title: String,
        val authors: String,
        val description: String,
        val thumbnailUrl: String?,
        val downloadUrl: String?, // Link diretto al file se disponibile
        val format: String? // Formato del file (es. "pdf", "epub")
    )

    /**
     * Cerca libri online su tutte le piattaforme configurate in parallelo.
     * @param query Il testo da cercare (titolo, autore, argomento).
     * @param langCode Il codice lingua (es. "it") per filtrare i risultati.
     */
    suspend fun searchLegalBooks(query: String, langCode: String = "it"): List<BookResult> {
        return coroutineScope {
            // Avvia le ricerche in parallelo (solo fonti legali)
            val googleDeferred = async(Dispatchers.IO) { searchGoogleBooks(query, langCode) }
            val gutenbergDeferred = async(Dispatchers.IO) { searchGutenberg(query, langCode) }
            val archiveDeferred = async(Dispatchers.IO) { searchInternetArchive(query) }

            val results = mutableListOf<BookResult>()
            
            // Attende tutti i risultati e li unisce
            val allResults = awaitAll(googleDeferred, gutenbergDeferred, archiveDeferred)
            allResults.forEach { results.addAll(it) }

            // Mischia i risultati per non avere blocchi separati per fonte
            results.shuffled()
        }
    }

    private suspend fun searchGoogleBooks(query: String, langCode: String): List<BookResult> {
            val results = mutableListOf<BookResult>()
            try {
                val response = client.get(GOOGLE_BOOKS_URL) {
                    parameter("q", query)
                    parameter("langRestrict", langCode)
                }.body<GoogleBooksResponse>()

                response.items?.forEach { item ->
                    val info = item.volumeInfo
                    val access = item.accessInfo
                    
                    var downloadUrl: String? = null
                    var format: String? = null

                    // Priorità EPUB
                    if (access?.epub?.isAvailable == true) {
                        downloadUrl = access.epub.downloadLink
                        format = "epub"
                    } else if (access?.pdf?.isAvailable == true) {
                        downloadUrl = access.pdf.downloadLink
                        format = "pdf"
                    }

                    // Fix HTTPS
                    if (downloadUrl?.startsWith("http://") == true) {
                        downloadUrl = downloadUrl!!.replace("http://", "https://")
                    }

                    var thumbnail = info?.imageLinks?.thumbnail
                    if (thumbnail?.startsWith("http://") == true) {
                        thumbnail = thumbnail!!.replace("http://", "https://")
                    }

                    if (downloadUrl != null) {
                        results.add(BookResult(
                            id = "google_${item.id}",
                            title = info?.title ?: "Titolo sconosciuto",
                            authors = info?.authors?.joinToString(", ") ?: "",
                            description = info?.description ?: "",
                            thumbnailUrl = thumbnail,
                            downloadUrl = downloadUrl,
                            format = format
                        ))
                    }
                }
            } catch (e: Exception) {
                println("$TAG: Errore Google Books: ${e.message}")
            }
            return results
    }

    private suspend fun searchGutenberg(query: String, langCode: String): List<BookResult> {
        val results = mutableListOf<BookResult>()
        try {
            val response = client.get(GUTENBERG_URL) {
                parameter("search", query)
            }.body<GutendexResponse>()

            response.results?.forEach { item ->
                // Filtro lingua
                if (item.languages.contains(langCode) || langCode == "en") {
                    
                    var downloadUrl: String? = null
                    var format: String? = null
                    
                    val formats = item.formats
                    
                    // Cerca copertina
                    var thumbnail = formats?.get("image/jpeg") ?: formats?.get("image/png")

                    // Cerca EPUB/PDF
                    if (formats?.containsKey("application/epub+zip") == true) {
                        downloadUrl = formats["application/epub+zip"]
                        format = "epub"
                    } else if (formats?.containsKey("application/pdf") == true) {
                        downloadUrl = formats["application/pdf"]
                        format = "pdf"
                    } else if (formats?.containsKey("text/plain; charset=utf-8") == true) {
                        downloadUrl = formats["text/plain; charset=utf-8"]
                        format = "txt"
                    }

                    if (downloadUrl != null) {
                        results.add(BookResult(
                            id = "gutenberg_${item.id}",
                            title = item.title ?: "Senza titolo",
                            authors = item.authors.joinToString(", ") { it.name ?: "" },
                            description = "Project Gutenberg Public Domain Text",
                            thumbnailUrl = thumbnail,
                            downloadUrl = downloadUrl,
                            format = format
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("$TAG: Errore Gutenberg: ${e.message}")
        }
        return results
    }

    private suspend fun searchInternetArchive(query: String): List<BookResult> {
        val results = mutableListOf<BookResult>()
        try {
            val q = "title:($query) AND mediatype:(texts)"
            val response = client.get(INTERNET_ARCHIVE_URL) {
                parameter("q", q)
                parameter("fl[]", listOf("identifier", "title", "creator", "description"))
                parameter("rows", "15")
                parameter("output", "json")
            }.body<IAResponse>()

            response.response?.docs?.forEach { item ->
                val id = item.identifier
                if (id != null) {
                    val downloadUrl = "https://archive.org/download/$id/$id.pdf"
                    val thumbnail = "https://archive.org/services/img/$id"

                    results.add(BookResult(
                        id = "ia_$id",
                        title = item.title ?: "Senza titolo",
                        authors = item.creator ?: "Unknown",
                        description = item.description ?: "Internet Archive Item",
                        thumbnailUrl = thumbnail,
                        downloadUrl = downloadUrl,
                        format = "pdf"
                    ))
                }
            }
        } catch (e: Exception) {
            println("$TAG: Errore Internet Archive: ${e.message}")
        }
        return results
    }
}

// --- DTOs per il Parsing JSON (Serializable) ---

@Serializable
data class GoogleBooksResponse(val items: List<GoogleBookItem>? = null)

@Serializable
data class GoogleBookItem(val id: String, val volumeInfo: GoogleVolumeInfo? = null, val accessInfo: GoogleAccessInfo? = null)

@Serializable
data class GoogleVolumeInfo(val title: String? = null, val authors: List<String>? = null, val description: String? = null, val imageLinks: GoogleImageLinks? = null)

@Serializable
data class GoogleImageLinks(val thumbnail: String? = null)

@Serializable
data class GoogleAccessInfo(val pdf: GoogleFormat? = null, val epub: GoogleFormat? = null)

@Serializable
data class GoogleFormat(val isAvailable: Boolean = false, val downloadLink: String? = null)

@Serializable
data class GutendexResponse(val results: List<GutendexBook>? = null)

@Serializable
data class GutendexBook(val id: Int, val title: String? = null, val authors: List<GutendexAuthor> = emptyList(), val languages: List<String> = emptyList(), val formats: Map<String, String>? = null)

@Serializable
data class GutendexAuthor(val name: String? = null)

@Serializable
data class IAResponse(val response: IADocs? = null)

@Serializable
data class IADocs(val docs: List<IADoc>? = null)

@Serializable
data class IADoc(val identifier: String? = null, val title: String? = null, val creator: String? = null, val description: String? = null)
