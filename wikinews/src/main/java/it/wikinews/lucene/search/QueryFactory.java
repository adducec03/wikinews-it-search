package it.wikinews.lucene.search;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Costruisce la query con i pesi di default (vedi SearchSettings).
     * Usato dai suggerimenti: lì conta solo QUALI articoli vengono trovati, e i boost
     * cambiano soltanto l'ordine, quindi i pesi configurati non servono.
     */
    public static Query build(Analyzer analyzer, String text, String category, LocalDate from, LocalDate to) throws ParseException {
        return build(analyzer, new SearchSettings(), text, category, from, to);
    }

    /** Costruisce la query usando i pesi indicati in settings. */
    public static Query build(Analyzer analyzer, SearchSettings settings, String text, String category,
                              LocalDate from, LocalDate to) throws ParseException {

        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        Query textQuery = buildTextQuery(text, analyzer, settings);

        builder.add(textQuery, BooleanClause.Occur.MUST);
        addProximityBoost(builder, analyzer, text, settings);
        addCategoryFilter(builder, analyzer, category);
        addDateFilter(builder, from, to);

        return builder.build();
    }

    private static Query buildTextQuery(String text, Analyzer analyzer, SearchSettings settings) throws ParseException {

        if (text == null || text.isBlank()) {
            return new MatchAllDocsQuery();
        }

        // campi in cui cercare, ognuno con il suo peso.
        // un campo con boost 0 non viene cercato (utile per l'ablation, es. "senza categorie")
        List<String> fieldList = new ArrayList<String>();
        Map<String, Float> boosts = new HashMap<String, Float>();
        addField(fieldList, boosts, IndexFields.NAME, settings.getNameBoost());
        addField(fieldList, boosts, IndexFields.ABSTRACT, settings.getAbstractBoost());
        addField(fieldList, boosts, IndexFields.CONTENT, settings.getContentBoost());
        addField(fieldList, boosts, IndexFields.CATEGORIES, settings.getCategoriesBoost());

        if (fieldList.isEmpty()) {
            throw new IllegalStateException("Configurazione non valida: almeno un campo deve avere boost maggiore di 0");
        }
        String[] fields = fieldList.toArray(new String[0]);

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

        // operatore tra le parole della query: AND = tutte obbligatorie, OR = ne basta una
        if (settings.isAndOperator()) {
            parser.setDefaultOperator(QueryParser.Operator.AND);
        } else {
            parser.setDefaultOperator(QueryParser.Operator.OR);
        }

        return parser.parse(text);
    }

    /** Aggiunge il campo alla ricerca solo se il suo boost è maggiore di 0. */
    private static void addField(List<String> fieldList, Map<String, Float> boosts, String field, float boost) {
        if (boost > 0) {
            fieldList.add(field);
            boosts.put(field, boost);
        }
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

    private static void addProximityBoost(BooleanQuery.Builder builder, Analyzer analyzer, String text,
                                          SearchSettings settings) {

        if (text == null || text.isBlank()) {
            return;
        }

        // boost di prossimità disattivato dalla configurazione
        if (settings.getProximityBoost() <= 0) {
            return;
        }

        int slop = settings.getProximitySlop();
        float boost = settings.getProximityBoost();

        // una sola parola: non ha senso parlare di vicinanza
        if (!text.trim().contains(" ")) {
            return;
        }

        // se l'utente usa già virgolette, +, -, ecc. lasciamo la query com'è
        if (usesAdvancedSyntax(text)) {
            return;
        }

        QueryBuilder queryBuilder = new QueryBuilder(analyzer);

        Query phraseInTitle = queryBuilder.createPhraseQuery(IndexFields.NAME, text, slop);
        if (phraseInTitle != null) {
            builder.add(new BoostQuery(phraseInTitle, boost), BooleanClause.Occur.SHOULD);
        }

        Query phraseInContent = queryBuilder.createPhraseQuery(IndexFields.CONTENT, text, slop);
        if (phraseInContent != null) {
            builder.add(new BoostQuery(phraseInContent, boost), BooleanClause.Occur.SHOULD);
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
