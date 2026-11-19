package it.wikinews.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.springframework.stereotype.Service;

import it.wikinews.lucene.index.IndexFields;

@Service
public class CategoryService {

    private final ArticleSearcher articleSearcher;
    private List<String> cachedCategories = new ArrayList<String>();

    public CategoryService(ArticleSearcher articleSearcher) {
        this.articleSearcher = articleSearcher;
    }

    public synchronized List<String> getAllCategories() throws IOException {

        // la lista viene calcolata una volta sola e poi riusata
        if (!cachedCategories.isEmpty()) {
            return cachedCategories;
        }

        TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

        IndexSearcher searcher = articleSearcher.acquireSearcher();
        try {
            IndexReader reader = searcher.getIndexReader();
            StoredFields storedFields = searcher.storedFields();

            // carichiamo solo il campo categorie, non il testo dell'articolo
            Set<String> fieldsToLoad = Set.of(IndexFields.CATEGORIES);

            for (int i = 0; i < reader.maxDoc(); i++) {
                Document doc = storedFields.document(i, fieldsToLoad);
                String stored = doc.get(IndexFields.CATEGORIES);

                if (stored == null || stored.isBlank()) {
                    continue;
                }

                String[] parts = stored.split(",");
                for (int j = 0; j < parts.length; j++) {
                    String name = parts[j].trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } finally {
            articleSearcher.releaseSearcher(searcher);
        }

        cachedCategories = new ArrayList<String>(names);
        return cachedCategories;
    }

    // metodo per ordinare i suggerimenti nella riceca delle categorie
    public List<String> suggest(String text, int limit) throws IOException {

        List<String> all = getAllCategories();
        List<String> result = new ArrayList<String>();

        String search = text.trim().toLowerCase();
        if (search.isEmpty()) {
            return result;
        }

        // 1) il nome inizia con il testo
        for (String name : all) {
            if (result.size() >= limit) {
                return result;
            }
            if (name.toLowerCase().startsWith(search)) {
                result.add(name);
            }
        }

        // 2) una parola del nome inizia con il testo ("Giochi a premi" per "pre")
        for (String name : all) {
            if (result.size() >= limit) {
                return result;
            }
            if (!result.contains(name) && name.toLowerCase().contains(" " + search)) {
                result.add(name);
            }
        }

        // 3) il testo compare in un punto qualunque
        for (String name : all) {
            if (result.size() >= limit) {
                return result;
            }
            if (!result.contains(name) && name.toLowerCase().contains(search)) {
                result.add(name);
            }
        }

        return result;
    }

}
