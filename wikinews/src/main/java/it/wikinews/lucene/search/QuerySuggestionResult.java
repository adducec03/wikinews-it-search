package it.wikinews.lucene.search;

import org.apache.lucene.search.Query;

public record QuerySuggestionResult(String text, Query query) {
}
