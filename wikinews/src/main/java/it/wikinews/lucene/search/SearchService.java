package it.wikinews.lucene.search;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.stereotype.Service;

import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.index.IndexFields;

@Service
public class SearchService {

    private static final int SNIPPET_FALLBACK_LENGTH = 250;

    private final ArticleSearcher articleSearcher;

    public SearchService(ArticleSearcher articleSearcher) {
        this.articleSearcher = articleSearcher;
    }

    /**
     * @param text     query utente (sintassi Lucene: +, -, "frase", campo:valore); può essere vuota se c'è un filtro
     * @param category filtro opzionale sulla categoria
     * @param from     filtro opzionale data modifica (inclusa)
     * @param to       filtro opzionale data modifica (inclusa)
     * @param sortByDate true = più recenti prima, false = rilevanza
     */
    public SearchResponse search(String text, String category, LocalDate from, LocalDate to,
                                 boolean sortByDate, int page, int pageSize) throws ParseException, IOException {
        long start = System.nanoTime();
        Analyzer analyzer = AnalyzerFactory.createAnalyzer();
        boolean hasText = text != null && !text.isBlank();
        Query query = QueryFactory.build(analyzer, text, category, from, to);
        IndexSearcher searcher = articleSearcher.acquireSearcher();
        try { 
            int safePage = Math.max(1, page);
            int upTo = safePage * pageSize;
            TopDocs top = articleSearcher.search(searcher, query, upTo, sortByDate);

            int fromIdx = Math.min((safePage - 1) * pageSize, top.scoreDocs.length);
            List<SearchResult> results = getSearchResults(fromIdx, query, top, searcher, analyzer, hasText);
            
            long took = (System.nanoTime() - start) / 1_000_000;
            return new SearchResponse(text, top.totalHits.value, safePage, pageSize, took, results);
        } finally {
            articleSearcher.releaseSearcher(searcher);
        }
    }

    private List<SearchResult> getSearchResults(int fromIdx, Query query, TopDocs top, IndexSearcher searcher, Analyzer analyzer, boolean hasText) throws IOException {

        ScoreDoc[] slice = Arrays.copyOfRange(top.scoreDocs, fromIdx, top.scoreDocs.length);

        String[] snippets = new String[slice.length];
        if (hasText && slice.length > 0) {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withFormatter(new DefaultPassageFormatter("<mark>", "</mark>", " … ", true))
                    .build();
            snippets = highlighter.highlight(IndexFields.CONTENT, query, new TopDocs(top.totalHits, slice), 2);
        }

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < slice.length; i++) {
            Document doc = searcher.storedFields().document(slice[i].doc);
            String snippet = snippets[i];
            if (snippet == null || snippet.isBlank()) {
                snippet = fallbackSnippet(doc);
            }
            Number millis = doc.getField(IndexFields.DATE_MODIFIED) != null
                    ? doc.getField(IndexFields.DATE_MODIFIED).numericValue() 
                    : null;
            results.add(new SearchResult(doc.get(IndexFields.NAME), doc.get(IndexFields.URL), snippet, millis != null ? new Date(millis.longValue()) : null, splitCategories(doc.get(IndexFields.CATEGORIES)), slice[i].score));
        }

        return results;
    }

    private static String fallbackSnippet(Document doc) {
        String s = doc.get(IndexFields.ABSTRACT);
        if (s == null || s.isBlank()) {
            s = doc.get(IndexFields.CONTENT);
        }
        if (s == null) {
            return "";
        }
        s = s.length() > SNIPPET_FALLBACK_LENGTH ? s.substring(0, SNIPPET_FALLBACK_LENGTH) + "…" : s;
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<String> splitCategories(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
    }
}
