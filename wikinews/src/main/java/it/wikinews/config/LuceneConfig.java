package it.wikinews.config;

import it.wikinews.lucene.LuceneManager;
import it.wikinews.lucene.index.ArticleIndexer;
import it.wikinews.lucene.search.ArticleSearcher;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuceneConfig {

    @Bean
    public ArticleIndexer articleIndexer(LuceneManager luceneManager) {
        return new ArticleIndexer(luceneManager.getIndexWriter());
    }

    @Bean
    public ArticleSearcher articleSearcher(LuceneManager luceneManager) {
        return new ArticleSearcher(luceneManager.getSearcherManager());
    }
}