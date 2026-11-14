package it.wikinews.lucene.search;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;

import org.apache.lucene.analysis.Analyzer;

import it.wikinews.lucene.index.IndexFields;

public class QueryFactory {


    public static Query build(Analyzer analyzer, String text, String category, LocalDate from, LocalDate to) throws ParseException {

        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        Query textQuery = buildTextQuery(text, analyzer);

        builder.add(textQuery, BooleanClause.Occur.MUST);
        addCategoryFilter(builder, analyzer, category);
        addDateFilter(builder, from, to);

        return builder.build();
    }

    private static Query buildTextQuery(String text, Analyzer analyzer) throws ParseException {

        if (text == null || text.isBlank()) {
            return new MatchAllDocsQuery();
        }

        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[] {
                    IndexFields.NAME,
                    IndexFields.ABSTRACT,
                    IndexFields.CONTENT,
                    IndexFields.CATEGORIES
                },
                analyzer,
                Map.of(
                    IndexFields.NAME, 3f,
                    IndexFields.ABSTRACT, 2f,
                    IndexFields.CONTENT, 1f,
                    IndexFields.CATEGORIES, 1f
                )
            );

        return parser.parse(text);
    }

    private static void addCategoryFilter(BooleanQuery.Builder builder, Analyzer analyzer, String category) throws ParseException {

        if (category == null || category.isBlank()) {
            return;
        }

        QueryParser parser = new QueryParser(IndexFields.CATEGORIES, analyzer);
        Query categoryQuery = parser.parse("\"" + QueryParser.escape(category.trim()) + "\"");
        builder.add(categoryQuery, BooleanClause.Occur.FILTER);
    }

    private static void addDateFilter(BooleanQuery.Builder builder, LocalDate from, LocalDate to) {

        if (from == null && to == null) {
            return;
        }

        long min = from != null
            ? from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            : Long.MIN_VALUE;

        long max = to != null
            ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
            : Long.MAX_VALUE;

        Query dateQuery = LongPoint.newRangeQuery(IndexFields.DATE_MODIFIED, min, max);
        builder.add(dateQuery, BooleanClause.Occur.FILTER);
    }
}
