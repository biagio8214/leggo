import SwiftUI
import shared

struct ContentView: View {
    @State private var books: [Book] = []

    var body: some View {
        NavigationView {
            List(books, id: \.uriString) { book in
                VStack(alignment: .leading) {
                    Text(book.title).font(.headline)
                    Text(book.uriString).font(.subheadline).foregroundColor(.gray)
                }
            }
            .navigationTitle("Leggo - Libri")
            .onAppear {
                loadBooks()
            }
        }
    }

    func loadBooks() {
        // Chiamata alla logica condivisa
        BookManager.shared.getRecentBooks { results, error in
            if let results = results {
                self.books = results
            }
        }
    }
}
