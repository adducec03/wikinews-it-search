package it.wikinews.config;

import it.wikinews.lucene.ArticleIndexer;
import it.wikinews.models.Article;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class IndexStartup implements CommandLineRunner {

    private final ArticleIndexer articleIndexer;

    public IndexStartup(ArticleIndexer articleIndexer) {
        this.articleIndexer = articleIndexer;
    }

    // Metodo dove viene eseguita l'indicizzazione all'avvio dell'applicazione
    @Override
    public void run(String... args) throws Exception {

        System.out.println("Avvio indicizzazione Wikinews...");

        // Esempio di indicizzazione di un articolo
        Article article = new Article();
        article.setName("Titolo dell'articolo");
        article.setId("12345"); 
        article.setAbstractContent("Abstract dell'articolo");
        article.setContent("Contenuto completo dell'articolo");
        article.setUrl("https://www.wikinews.org/articolo");
        article.setDateModified(new java.util.Date());

        articleIndexer.index(article);
        articleIndexer.commit();

        System.out.println("Indicizzazione completata!");
    }
}