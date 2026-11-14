package it.wikinews.importer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import it.wikinews.models.Article;

import org.jsoup.Jsoup;
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
        String html = node.path("article_body").path("html").asText();
        article.setContent(extractTextFromHtml(html));
        return article;
    }

    public String extractTextFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        return Jsoup.parse(html).text();
    }
}