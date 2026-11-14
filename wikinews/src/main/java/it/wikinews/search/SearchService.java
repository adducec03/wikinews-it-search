package it.wikinews.search;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.stereotype.Service;

import it.wikinews.lucene.IndexFields;
import it.wikinews.lucene.IndexManager;

@Service
public class SearchService {

    private static final int SNIPPET_FALLBACK_LENGTH = 250;

    private final IndexManager indexManager;

    public SearchService(IndexManager indexManager) {
        this.indexManager = indexManager;
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
        var analyzer = indexManager.getAnalyzer();

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasText = text != null && !text.isBlank();
        Query userQuery;
        if (hasText) {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[] { IndexFields.NAME, IndexFields.ABSTRACT, IndexFields.CONTENT, IndexFields.CATEGORIES },
                    analyzer,
                    Map.of(IndexFields.NAME, 3f, IndexFields.ABSTRACT, 2f, IndexFields.CONTENT, 1f, IndexFields.CATEGORIES, 1f));
            userQuery = parser.parse(text);
        } else {
            userQuery = new MatchAllDocsQuery();
        }
        builder.add(userQuery, BooleanClause.Occur.MUST);

        if (category != null && !category.isBlank()) {
            QueryParser cp = new QueryParser(IndexFields.CATEGORIES, analyzer);
            builder.add(cp.parse("\"" + QueryParser.escape(category.trim()) + "\""), BooleanClause.Occur.FILTER);
        }
        if (from != null || to != null) {
            long lo = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() : Long.MIN_VALUE;
            long hi = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1 : Long.MAX_VALUE;
            builder.add(LongPoint.newRangeQuery(IndexFields.DATE_MODIFIED, lo, hi), BooleanClause.Occur.FILTER);
        }
        Query query = builder.build();

        var manager = indexManager.getSearcherManager();
        manager.maybeRefresh();
        IndexSearcher searcher = manager.acquire();
        try {
            int safePage = Math.max(1, page);
            int upTo = safePage * pageSize;
            TopDocs top = sortByDate
                    ? searcher.search(query, upTo, new Sort(new SortField(IndexFields.DATE_MODIFIED, SortField.Type.LONG, true)))
                    : searcher.search(query, upTo);

            int fromIdx = Math.min((safePage - 1) * pageSize, top.scoreDocs.length);
            ScoreDoc[] slice = Arrays.copyOfRange(top.scoreDocs, fromIdx, top.scoreDocs.length);

            String[] snippets = new String[slice.length];
            if (hasText && slice.length > 0) {
                UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                        .withFormatter(new DefaultPassageFormatter("<mark>", "</mark>", " … ", true))
                        .build();
                snippets = highlighter.highlight(IndexFields.CONTENT, userQuery, new TopDocs(top.totalHits, slice), 2);
            }

            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < slice.length; i++) {
                Document doc = searcher.storedFields().document(slice[i].doc);
                String snippet = snippets[i];
                if (snippet == null || snippet.isBlank()) {
                    snippet = fallbackSnippet(doc);
                }
                Number millis = doc.getField(IndexFields.DATE_MODIFIED) != null
                        ? doc.getField(IndexFields.DATE_MODIFIED).numericValue() : null;
                results.add(new SearchResult(
                        doc.get(IndexFields.NAME),
                        doc.get(IndexFields.URL),
                        snippet,
                        millis != null ? new Date(millis.longValue()) : null,
                        splitCategories(doc.get(IndexFields.CATEGORIES)),
                        slice[i].score));
            }
            long took = (System.nanoTime() - start) / 1_000_000;
            return new SearchResponse(text, top.totalHits.value, safePage, pageSize, took, results);
        } finally {
            manager.release(searcher);
        }
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
