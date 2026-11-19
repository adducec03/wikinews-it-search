package it.wikinews.lucene.index;

import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;

import it.wikinews.models.Article;

public class ArticleIndexer {
    private final IndexWriter indexWriter;

    public ArticleIndexer(IndexWriter indexWriter) {
        this.indexWriter = indexWriter;
    }

    /**
     * Aggiunge l'articolo all'indice evitando i duplicati.
     *
     * Nel dump 48 articoli compaiono due volte con lo stesso "identifier":
     * la seconda copia è sempre una revisione più recente della prima.
     * Con updateDocument Lucene cancella il documento che ha lo stesso id
     * e aggiunge quello nuovo, quindi nell'indice resta una sola copia: l'ultima letta.
     */
    public void index(Article article) {
        Document document = this.articleToDocument(article);
        String id = valueOrEmpty(article.getId());

        try {
            if (id.isEmpty()) {
                // senza id non possiamo riconoscere i duplicati: aggiungiamo il documento e basta
                indexWriter.addDocument(document);
            } else {
                // il Term indica quale documento sostituire: quello con questo valore nel campo "id"
                Term idTerm = new Term(IndexFields.ID, id);
                indexWriter.updateDocument(idTerm, document);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void commit() throws IOException {
        indexWriter.commit();
    }

    /**
     * Numero di documenti presenti nell'indice.
     * Non conta quelli sostituiti da updateDocument, quindi è il numero di articoli distinti.
     */
    public int countDocuments() {
        return indexWriter.getDocStats().numDocs;
    }

    private Document articleToDocument(Article article) {
        Document document = new Document();

        // Lucene non accetta valori null nei campi: un campo mancante nel dump
        // (es. 266 articoli senza "abstract") diventa una stringa vuota
        String name = valueOrEmpty(article.getName());
        String content = valueOrEmpty(article.getContent());
        String abstractContent = valueOrEmpty(article.getAbstractContent());
        String url = valueOrEmpty(article.getUrl());
        String id = valueOrEmpty(article.getId());

        document.add(new TextField(IndexFields.NAME, name, Field.Store.YES));
        document.add(new TextField(IndexFields.CONTENT, content, Field.Store.YES));
        document.add(new TextField(IndexFields.ABSTRACT, abstractContent, Field.Store.YES));
        document.add(new StringField(IndexFields.URL, url, Field.Store.YES));

        // senza data l'articolo resta cercabile, ma non viene trovato dai filtri per data
        if (article.getDateModified() != null) {
            long millis = article.getDateModified().getTime();
            document.add(new LongPoint(IndexFields.DATE_MODIFIED, millis));
            document.add(new StoredField(IndexFields.DATE_MODIFIED, millis));
            document.add(new NumericDocValuesField(IndexFields.DATE_MODIFIED, millis));
        }

        document.add(new TextField(IndexFields.CATEGORIES, article.getCategoriesAsString(), Field.Store.YES));
        document.add(new StringField(IndexFields.ID, id, Field.Store.YES));

        String suggestTerms = name + " " + abstractContent;
        document.add(new TextField(IndexFields.SUGGEST_TERMS, suggestTerms, Field.Store.NO));
        return document;
    }

    private static String valueOrEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}