package it.wikinews.search;

import java.util.Date;
import java.util.List;

public record SearchResult(String title, String url, String snippet, Date date, List<String> categories, float score) {
}
