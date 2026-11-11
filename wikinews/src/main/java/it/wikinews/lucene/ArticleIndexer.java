package it.wikinews.lucene;

import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;

import it.wikinews.models.Article;

public class ArticleIndexer {
    private final IndexWriter indexWriter;

    public ArticleIndexer(IndexWriter indexWriter) {
        this.indexWriter = indexWriter;
    }

    public void index(Article article) {
        Document document = this.articleToDocument(article);
        try {
            indexWriter.addDocument(document);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void commit() throws IOException {
        indexWriter.commit();
    }

    private Document articleToDocument(Article article) {
        Document document = new Document();
    
        document.add(new TextField(IndexFields.NAME, article.getName(), Field.Store.YES));
        document.add(new TextField(IndexFields.CONTENT, article.getContent(), Field.Store.YES));
        document.add(new TextField(IndexFields.ABSTRACT, article.getAbstractContent(), Field.Store.YES));
        document.add(new StringField(IndexFields.URL, article.getUrl(), Field.Store.YES));
        document.add(new LongPoint(IndexFields.DATE_MODIFIED, article.getDateModified().getTime()));
        document.add(new TextField(IndexFields.CATEGORIES, article.getCategoriesAsString(), Field.Store.YES));
        document.add(new StringField(IndexFields.ID, article.getId(), Field.Store.YES));
        return document;
    }
}