package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.lucene.codecs.simpletext.SimpleTextCodec;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.importer.ArticleParser;
import it.wikinews.importer.JsonReader;
import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.index.ArticleIndexer;
import it.wikinews.lucene.search.ArticleSearcher;
import it.wikinews.lucene.search.QuerySuggestionService;
import it.wikinews.lucene.search.SearchService;

/**
 * Efficienza sull'indice COMPLETO (tutto il dump), con due codec a confronto:
 * - SimpleTextCodec: quello usato oggi dall'applicazione (indice in formato testo, pensato per il debug);
 * - codec di default di Lucene (binario e compresso).
 *
 * Per ogni codec:
 * 1. indicizza tutto il dump in una cartella nuova (target/efficiency/...) e misura
 *    tempo totale, articoli al secondo, documenti nell'indice, dimensione su disco;
 * 2. esegue le query di evaluation/queries.tsv come le esegue la UI (prima pagina, 10 risultati,
 *    snippet compresi): WARMUP_ROUNDS giri di riscaldamento non misurati, poi MEASURED_ROUNDS giri
 *    misurati. Stampa latenza media, mediana, p95, massima e ricerche al secondo.
 *
 * Il riscaldamento serve perché le prime esecuzioni sono molto più lente (compilazione JIT
 * della JVM, cache): non rappresentano il funzionamento a regime.
 *
 * L'applicazione avviata dal test indicizza solo 1 articolo (maxArticle=1) in una cartella
 * separata: serve solo a ottenere i componenti di Spring (lettore del dump e parser).
 *
 * Lancio:  ./mvnw test -Dtest=EfficiencyEvaluationTest -DexcludedGroups=none
 */
@Tag("evaluation")
@SpringBootTest(properties = {
        "wikinews.data.maxArticle=1",
        "wikinews.index.path=target/efficiency/startup-index"
})
class EfficiencyEvaluationTest {

    /** Giri di riscaldamento (non misurati) su tutte le query. */
    private static final int WARMUP_ROUNDS = 5;

    /** Giri misurati su tutte le query. */
    private static final int MEASURED_ROUNDS = 20;

    /** Risultati per pagina, come nella UI. */
    private static final int PAGE_SIZE = 10;

    private static final Path OUTPUT = Path.of("target", "evaluation", "efficiency.tsv");

    @Autowired
    private JsonReader jsonReader;

    @Autowired
    private ArticleParser articleParser;

    @Value("${wikinews.data.path}")
    private Path dumpPath;

    // contatori dell'indicizzazione in corso
    private int indexedCount;
    private int errorCount;

    @Test
    void measureEfficiency() throws Exception {
        Map<String, String> queries = EvaluationData.readQueries();
        List<String> lines = new ArrayList<String>();

        System.out.println();
        System.out.println("=== EFFICIENZA SULL'INDICE COMPLETO ===");
        System.out.println("Dump: " + dumpPath + " (" + formatMegabytes(Files.size(dumpPath)) + ")");

        lines.add(measureCodec("SimpleTextCodec (attuale)", true, queries));
        lines.add(measureCodec("codec di default", false, queries));

        System.out.println();
        String header = String.format("%-26s %8s %9s %8s %10s %9s %9s %9s %9s %8s",
                "codec", "tempo(s)", "art/s", "doc", "indice", "media", "mediana", "p95", "max", "ric/s");
        System.out.println(header);
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println("(latenze in ms, su " + queries.size() + " query x " + MEASURED_ROUNDS
                + " giri, dopo " + WARMUP_ROUNDS + " giri di riscaldamento)");

        Files.createDirectories(OUTPUT.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {
            writer.write(header);
            writer.newLine();
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
        System.out.println("Risultati salvati in " + OUTPUT.toAbsolutePath());
    }

    /** Indicizza tutto il dump con il codec indicato, misura le query e restituisce la riga della tabella. */
    private String measureCodec(String name, boolean simpleText, Map<String, String> queries) throws Exception {
        Path indexPath = Path.of("target", "efficiency", simpleText ? "simpletext-index" : "default-index");
        deleteDirectory(indexPath);
        Files.createDirectories(indexPath);

        try (Directory directory = FSDirectory.open(indexPath)) {
            IndexWriterConfig config = new IndexWriterConfig(AnalyzerFactory.createAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            if (simpleText) {
                config.setCodec(new SimpleTextCodec());
            }

            try (IndexWriter writer = new IndexWriter(directory, config)) {
                // ---- 1. indicizzazione ----
                ArticleIndexer indexer = new ArticleIndexer(writer);
                indexedCount = 0;
                errorCount = 0;

                long start = System.nanoTime();
                jsonReader.read(dumpPath, json -> indexOne(indexer, json));
                indexer.commit();
                double seconds = (System.nanoTime() - start) / 1e9;

                int documents = indexer.countDocuments();
                long indexBytes = directorySize(indexPath);
                double articlesPerSecond = indexedCount / seconds;

                System.out.println();
                System.out.println("--- " + name + " ---");
                System.out.printf("Indicizzazione: %.1f s, %d articoli (%.0f al secondo), errori: %d%n",
                        seconds, indexedCount, articlesPerSecond, errorCount);
                System.out.println("Documenti nell'indice: " + documents
                        + ", dimensione: " + formatMegabytes(indexBytes));
                assertTrue(documents > 0, "Indice vuoto");

                // ---- 2. latenza delle ricerche ----
                try (SearcherManager searcherManager = new SearcherManager(writer, null)) {
                    SearchService service = new SearchService(
                            new ArticleSearcher(searcherManager), new QuerySuggestionService());

                    for (int round = 0; round < WARMUP_ROUNDS; round++) {
                        for (String text : queries.values()) {
                            service.search(text, "", null, null, false, 1, PAGE_SIZE);
                        }
                    }

                    List<Double> times = new ArrayList<Double>();
                    for (int round = 0; round < MEASURED_ROUNDS; round++) {
                        for (String text : queries.values()) {
                            long t0 = System.nanoTime();
                            service.search(text, "", null, null, false, 1, PAGE_SIZE);
                            times.add((System.nanoTime() - t0) / 1e6);
                        }
                    }

                    Collections.sort(times);
                    double mean = average(times);
                    double median = percentile(times, 50);
                    double p95 = percentile(times, 95);
                    double max = times.get(times.size() - 1);

                    System.out.printf("Latenza (ms): media %.2f, mediana %.2f, p95 %.2f, max %.2f; %.0f ricerche al secondo%n",
                            mean, median, p95, max, 1000 / mean);

                    return String.format("%-26s %8.1f %9.0f %8d %10s %9.2f %9.2f %9.2f %9.2f %8.0f",
                            name, seconds, articlesPerSecond, documents, formatMegabytes(indexBytes),
                            mean, median, p95, max, 1000 / mean);
                }
            }
        }
    }

    /** Indicizza una riga del dump; un errore scarta solo quell'articolo. */
    private void indexOne(ArticleIndexer indexer, String json) {
        try {
            indexer.index(articleParser.parse(json));
            indexedCount++;
        } catch (Exception e) {
            errorCount++;
        }
    }

    private static double average(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum = sum + value;
        }
        return sum / values.size();
    }

    /**
     * Percentile su una lista GIA' ORDINATA: il valore sotto cui sta il "percent"% delle misure
     * (es. p95 = il 95% delle ricerche è stato più veloce di questo valore).
     */
    private static double percentile(List<Double> sortedValues, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * sortedValues.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        return sortedValues.get(index);
    }

    /** Somma delle dimensioni dei file nella cartella dell'indice. */
    private static long directorySize(Path directory) throws IOException {
        long total = 0;
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> list = files.toList();
            for (Path file : list) {
                if (Files.isRegularFile(file)) {
                    total = total + Files.size(file);
                }
            }
        }
        return total;
    }

    /** Cancella la cartella e il suo contenuto (l'indice di un'esecuzione precedente). */
    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> list = files.toList();
            for (Path file : list) {
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }

    private static String formatMegabytes(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
