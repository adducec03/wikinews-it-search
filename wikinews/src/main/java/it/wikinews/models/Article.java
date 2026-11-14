package it.wikinews.models;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Article {
    @JsonProperty("name")
    private String name;
    @JsonProperty("identifier")
    private String id;
    @JsonProperty("abstract")
    private String abstractContent;
    private String content;
    @JsonProperty("url")
    private String url;
    @JsonProperty("date_modified")
    private Date dateModified;
    private List<Category> categories = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAbstractContent() {
        return abstractContent;
    }

    public void setAbstractContent(String abstractContent) {
        this.abstractContent = abstractContent;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Date getDateModified() {
        return dateModified;
    }

    public void setDateModified(Date dateModified) {
        this.dateModified = dateModified;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public String getCategoriesAsString() {
        StringBuilder categoriesString = new StringBuilder();
        for (Category category : categories) {
            categoriesString.append(category.getName()).append(", ");
        }
        return categoriesString.toString();
    }

    public void setCategories(List<Category> categories) {
        for (Category category : categories) {
            if (category.getName() != null) {
                category.setName(category.getName().replaceAll("(?i)^Categoria\\s*:\\s*", ""));
            }
            this.categories.add(category);
        }
    }
}
