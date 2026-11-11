package it.wikinews.lucene;

import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;

public class AnalyzerFactory {
    public static Analyzer createAnalyzer() {
        Analyzer italianAnalyzer = new ItalianAnalyzer();
        Map<String, Analyzer> byField = new HashMap<>();

        return new PerFieldAnalyzerWrapper(italianAnalyzer, byField);
    }
}
