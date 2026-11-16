package it.wikinews.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.index.IndexFields;

@Service
public class QuerySuggestionService {

    private final DirectSpellChecker spellChecker;

    public QuerySuggestionService() {
        this.spellChecker = new DirectSpellChecker();

        spellChecker.setMaxEdits(1);
        spellChecker.setAccuracy(0.5f);
        spellChecker.setMinQueryLength(3);
        spellChecker.setMaxInspections(10);
    }

     public String suggest(String query, IndexReader reader) throws IOException {
        if (query.contains("+") || query.contains("-") || query.contains("\"")) {
            return null;
        }
        List<String> tokens = analyze(query);


        StringJoiner suggestedQuery = new StringJoiner(" ");

        for (String token : tokens) {
            SuggestWord[] suggestions = spellChecker.suggestSimilar(new Term(IndexFields.SUGGEST_TERMS, token), 1, reader, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX);
            if (suggestions.length > 0) {
                suggestedQuery.add(suggestions[0].string);
            } 
            else {
                suggestedQuery.add(token);
            }
        }

        String result = suggestedQuery.toString();
        return result.equalsIgnoreCase(query) ? null : result;
    }

    private List<String> analyze(String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = AnalyzerFactory.createAnalyzer().tokenStream(IndexFields.SUGGEST_TERMS, text)) {
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
