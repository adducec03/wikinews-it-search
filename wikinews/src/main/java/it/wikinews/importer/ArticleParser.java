package it.wikinews.importer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import it.wikinews.models.Article;

import org.springframework.stereotype.Component;

@Component
public class ArticleParser {

    private final ObjectMapper objectMapper;

    public ArticleParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Article parse(String json) throws Exception {
        Article article = objectMapper.readValue(json, Article.class);
        JsonNode node = objectMapper.readTree(json);
        article.setContent(node.path("article_body").path("html").toString());
        return article;
    }
}