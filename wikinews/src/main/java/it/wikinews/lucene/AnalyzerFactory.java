package it.wikinews.lucene;

import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;

public class AnalyzerFactory {
    public static Analyzer createAnalyzer() {
        Analyzer italianAnalyzer = new ItalianAnalyzer();
        CharArraySet stopwords = new CharArraySet(ItalianAnalyzer.getDefaultStopSet(), true);
        stopwords.add("pubblicati");
        stopwords.add("articoli");
        Analyzer categoryAnalyzer = new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                TokenStream tokenStream = new LowerCaseFilter(tokenizer);
                tokenStream = new StopFilter(tokenStream, stopwords);
                return new TokenStreamComponents(tokenizer, tokenStream);
            }
        };
        Map<String, Analyzer> byField = Map.of(
            IndexFields.CATEGORIES, categoryAnalyzer
        );

        return new PerFieldAnalyzerWrapper(italianAnalyzer, byField);
    }
}
