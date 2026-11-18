package it.wikinews.lucene.search;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestWord;
import org.springframework.stereotype.Service;

import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.index.IndexFields;

@Service
public class QuerySuggestionService {

    private final DirectSpellChecker spellChecker;
    private final Analyzer analyzer = AnalyzerFactory.createAnalyzer();
    private static final int MAX_SUGGESTIONS_PER_TOKEN = 8;
    private static final int MAX_TOKENS = 6;
    private static final int MAX_ATTEMPTS = 50;

    public QuerySuggestionService() {
        this.spellChecker = new DirectSpellChecker();

        spellChecker.setMaxEdits(2);
        spellChecker.setAccuracy(0.5f);
        spellChecker.setMinQueryLength(3);
        spellChecker.setMaxInspections(10);
    }

     public QuerySuggestionResult suggest(String query, String category, LocalDate from, LocalDate to, IndexSearcher searcher) throws IOException, ParseException {
        if (query.contains("+") || query.contains("-") || query.contains("\""))
            return null;

        List<String> tokens = analyze(query);

        if (tokens.isEmpty() || tokens.size() > MAX_TOKENS) 
            return null;

        IndexReader reader = searcher.getIndexReader();

        if (tokens.size() == 1)
            return suggestBest(tokens.get(0), category, from, to, searcher, reader);
        else
            return suggestFirst(tokens, category, from, to, searcher, reader);
    }

    private QuerySuggestionResult suggestBest(String token, String category, LocalDate from, LocalDate to, IndexSearcher searcher, IndexReader reader) throws IOException, ParseException {

        Term term = new Term(IndexFields.SUGGEST_TERMS, token);
         if (reader.docFreq(term) > 0) return null;

        SuggestWord[] suggestions = spellChecker.suggestSimilar(term, MAX_SUGGESTIONS_PER_TOKEN, reader, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX);

        Query bestQuery = null;
        String best = null;
        float bestScore = 0f;
        for (SuggestWord s : suggestions) {
            Query query = QueryFactory.build(analyzer, s.string, category, from, to);
            TopDocs top = getTopDocs(query, searcher);
            if (top.scoreDocs.length == 0) 
                continue;
            if (top.scoreDocs[0].score > bestScore) {
                bestScore = top.scoreDocs[0].score;
                bestQuery = query;
                best = s.string;
            }
        }
        return new QuerySuggestionResult(best, bestQuery);
    }

    private QuerySuggestionResult suggestFirst(List<String> tokens, String category, LocalDate from, LocalDate to, IndexSearcher searcher, IndexReader reader) throws IOException, ParseException {
        
        int n = tokens.size();
        StringJoiner corrected = new StringJoiner(" ");
        Map<String, List<String>> suggestionsMap = new HashMap<>();

        for (String token : tokens) {
            Term term = new Term(IndexFields.SUGGEST_TERMS, token);
            List<String> suggestions = new ArrayList<>();
            if (reader.docFreq(term) > 0) {
                corrected.add(token);
            }
            else {
                SuggestWord[] s = spellChecker.suggestSimilar(term, MAX_SUGGESTIONS_PER_TOKEN, reader, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX);
                suggestions.addAll(Arrays.stream(s).map(w -> w.string).toList());
            }
            suggestionsMap.put(token, suggestions);
        }
        String[] current = new String[n];
        List<Integer> toFix = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (suggestionsMap.get(tokens.get(i)).isEmpty()) {
                current[i] = tokens.get(i);
            } 
            else {
                current[i] = suggestionsMap.get(tokens.get(i)).get(0);
                toFix.add(i);
            }
        }

        if (toFix.isEmpty()) return null;
        toFix.sort(Comparator.comparingInt((Integer i) -> suggestionsMap.get(tokens.get(i)).size()).reversed());
        int attempts = 0;
        for (int i : toFix) {
            List<String> suggestions = suggestionsMap.get(tokens.get(i));

            for (String s : suggestions) {
                if (attempts >= MAX_ATTEMPTS) {
                    return null;
                }
                if (isStopword(s)) {
                    continue;
                }
                attempts++;
                current[i] = s;
                Query query = QueryFactory.build(analyzer, String.join(" ", current), category, from, to);
                TopDocs top = getTopDocs(query, searcher);
                if (top.scoreDocs.length > 0) {
                    return new QuerySuggestionResult(String.join(" ", current), query);
                }
            }
            current[i] = suggestions.get(0);
        }

        return null;      
    }

    private TopDocs getTopDocs(Query query, IndexSearcher searcher) throws IOException, ParseException {
        TopDocs top = searcher.search(query, 1);
        return top;
    }

    private boolean isStopword(String word) throws IOException {
        try (TokenStream ts = analyzer.tokenStream(IndexFields.CONTENT, word)) {
            ts.reset();
            boolean hasToken = ts.incrementToken();
            ts.end();
            return !hasToken;
        }
    }

    private List<String> analyze(String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(IndexFields.SUGGEST_TERMS, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);

            tokenStream.reset();

            while (tokenStream.incrementToken()) {
                tokens.add(termAttribute.toString());
            }

            tokenStream.end();
        }
        return tokens;
    }
}