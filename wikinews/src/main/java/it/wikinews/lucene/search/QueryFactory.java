package it.wikinews.lucene.search;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.analysis.Analyzer;

import it.wikinews.lucene.index.IndexFields;

public class QueryFactory {

    // quante posizioni di distanza sono tollerate tra le parole
    // es query "elezioni comunali roma"
    // per il testo "elezioni comunali a roma" rientra nel proximity slop e ha proximity boost
    // per il testo "elezioni comunali [..500 parole...] roma" non ha il proximity boost
    private static final int PROXIMITY_SLOP = 3;
    // quanto boosta se le parole rientrano in proximity slop
    private static final float PROXIMITY_BOOST = 2.0f;

    // termine nel titolo vale x3
    private static final float NAME_BOOST = 3f;
    // termine nell'abstract vale x2
    private static final float ABSTRACT_BOOST = 2f;
    // termine nel contenuto vale x1
    private static final float CONTENT_BOOST = 1f;
    // termine nella categoria vale x1
    private static final float CATEGORIES_BOOST = 1f;



    public static Query build(Analyzer analyzer, String text, String category, LocalDate from, LocalDate to) throws ParseException {

        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        Query textQuery = buildTextQuery(text, analyzer);

        builder.add(textQuery, BooleanClause.Occur.MUST);
        addProximityBoost(builder, analyzer, text);
        addCategoryFilter(builder, analyzer, category);
        addDateFilter(builder, from, to);

        return builder.build();
    }

    private static Query buildTextQuery(String text, Analyzer analyzer) throws ParseException {

        if (text == null || text.isBlank()) {
            return new MatchAllDocsQuery();
        }

            String[] fields = new String[] {
            IndexFields.NAME,
            IndexFields.ABSTRACT,
            IndexFields.CONTENT,
            IndexFields.CATEGORIES
        };

        Map<String, Float> boosts = new HashMap<String, Float>();
        boosts.put(IndexFields.NAME, NAME_BOOST);
        boosts.put(IndexFields.ABSTRACT, ABSTRACT_BOOST);
        boosts.put(IndexFields.CONTENT, CONTENT_BOOST);
        boosts.put(IndexFields.CATEGORIES, CATEGORIES_BOOST);

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

        parser.setDefaultOperator(QueryParser.Operator.AND);

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

    private static void addProximityBoost(BooleanQuery.Builder builder, Analyzer analyzer, String text) {

        if (text == null || text.isBlank()) {
            return;
        }

        // una sola parola: non ha senso parlare di vicinanza
        if (!text.trim().contains(" ")) {
            return;
        }

        // se l'utente usa già virgolette, +, -, ecc. lasciamo la query com'è
        if (usesAdvancedSyntax(text)) {
            return;
        }

        QueryBuilder queryBuilder = new QueryBuilder(analyzer);

        Query phraseInTitle = queryBuilder.createPhraseQuery(IndexFields.NAME, text, PROXIMITY_SLOP);
        if (phraseInTitle != null) {
            builder.add(new BoostQuery(phraseInTitle, PROXIMITY_BOOST), BooleanClause.Occur.SHOULD);
        }

        Query phraseInContent = queryBuilder.createPhraseQuery(IndexFields.CONTENT, text, PROXIMITY_SLOP);
        if (phraseInContent != null) {
            builder.add(new BoostQuery(phraseInContent, PROXIMITY_BOOST), BooleanClause.Occur.SHOULD);
        }
    }

    private static boolean usesAdvancedSyntax(String text) {

        String specialCharacters = "\"+-:*?~^()[]{}";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (specialCharacters.indexOf(c) >= 0) {
                return true;
            }
        }

        if (text.contains(" AND ") || text.contains(" OR ") || text.contains("NOT ")) {
            return true;
        }

        return false;
    }

}
