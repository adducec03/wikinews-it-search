package it.wikinews.importer;

import it.wikinews.models.Article;
import it.wikinews.lucene.ArticleIndexer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ImportService {

    private final JsonReader jsonReader;
    private final ArticleParser articleParser;
    private final ArticleIndexer articleIndexer;

    @Value("${wikinews.data.path}")
    private Path dumpPath;

    public ImportService(JsonReader jsonReader, ArticleParser articleParser, ArticleIndexer articleIndexer) {
        this.jsonReader = jsonReader;
        this.articleParser = articleParser;
        this.articleIndexer = articleIndexer;
    }

    public void importArticles() throws Exception {

        int[] count = {0};
        jsonReader.read(dumpPath, json -> {
            try {
                if (count[0] > 10) {
                    return;
                }
                Article article = articleParser.parse(json);
                articleIndexer.index(article);
                count[0]++;
            } catch (Exception e) {
                System.err.println("Errore articolo[" + count[0] + "]: " + e.getMessage());
            }
        });
        articleIndexer.commit();
        System.out.println("Totale articoli importati: " + count[0]);
    }
}