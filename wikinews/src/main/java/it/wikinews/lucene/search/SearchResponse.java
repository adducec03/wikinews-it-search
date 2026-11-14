package it.wikinews.lucene.search;

import java.util.List;

public record SearchResponse(String query, long totalHits, int page, int pageSize, long tookMs, List<SearchResult> results) {

    public int totalPages() {
        return (int) Math.max(1, (totalHits + pageSize - 1) / pageSize);
    }
}
