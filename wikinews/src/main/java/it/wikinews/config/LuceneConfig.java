package it.wikinews.config;

import it.wikinews.lucene.ArticleIndexer;
import it.wikinews.lucene.IndexManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuceneConfig {

    @Bean
    public ArticleIndexer articleIndexer(IndexManager indexManager) {
        return new ArticleIndexer(indexManager.getIndexWriter());
    }
}