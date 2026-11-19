package it.wikinews.importer;

import it.wikinews.lucene.index.ArticleIndexer;
import it.wikinews.models.Article;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ImportService {

    private final JsonReader jsonReader;
    private final ArticleParser articleParser;
    private final ArticleIndexer articleIndexer;

    @Value ("${wikinews.data.maxArticle}")
    private Integer MAX_ARTICLE_IMPORT;

    @Value("${wikinews.data.path}")
    private Path dumpPath;

    public ImportService(JsonReader jsonReader, ArticleParser articleParser, ArticleIndexer articleIndexer) {
        this.jsonReader = jsonReader;
        this.articleParser = articleParser;
        this.articleIndexer = articleIndexer;
    }

    public void importArticles() throws Exception {

        // array di un elemento perché dentro la lambda si possono modificare solo oggetti, non variabili locali
        int[] count = {0};
        int[] errors = {0};
        jsonReader.read(dumpPath, json -> {
            try {
                if (count[0] > MAX_ARTICLE_IMPORT) {
                    return;
                }
                Article article = articleParser.parse(json);
                articleIndexer.index(article);
                count[0]++;
            } catch (Exception e) {
                errors[0]++;
                System.err.println("Errore articolo[" + count[0] + "]: " + e.getMessage());
            }
        });
        articleIndexer.commit();
        System.out.println("Totale articoli importati: " + count[0] + ", scartati per errore: " + errors[0]);
        // può essere minore degli articoli importati: i duplicati (stesso id) si sostituiscono a vicenda
        System.out.println("Documenti presenti nell'indice: " + articleIndexer.countDocuments());
    }
}