package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;
import it.wikinews.lucene.search.SearchSettings;

/**
 * Prepara il "pool" di articoli da giudicare a mano (tecnica del pooling).
 *
 * Per ogni query di QUERIES_TO_JUDGE prende i primi POOL_DEPTH risultati di OGNI configurazione
 * dell'ablation (AblationConfigurations) e li unisce. Così anche gli articoli trovati da una sola
 * configurazione (es. solo con OR) vengono giudicati e il confronto è equo.
 *
 * Il risultato è target/evaluation/pool.tsv, con la colonna "relevance" da compilare:
 *   0 = non rilevante, 1 = in parte rilevante, 2 = molto rilevante
 * leggendo il bisogno informativo ("need") della query in queries.tsv.
 * Le righe sono in ordine di titolo (non di posizione) per non influenzare chi giudica.
 * Il file giudicato va salvato come src/test/resources/evaluation/qrels.tsv.
 *
 * Se qrels.tsv esiste già, i voti presenti vengono ricopiati nel nuovo pool:
 * rigenerando il pool non si perde il lavoro fatto.
 *
 * Lancio:  ./mvnw test -Dtest=PoolBuilderTest -DexcludedGroups=none
 */
@Tag("evaluation")
@SpringBootTest(properties = {
        "wikinews.data.maxArticle=20000",
        "wikinews.index.path=target/evaluation-index"
})
class PoolBuilderTest {

    /** Quanti risultati per query e per configurazione mettere nel pool. */
    private static final int POOL_DEPTH = 10;

    /**
     * Le query da giudicare: un sottoinsieme delle 28 di queries.tsv, scelte per coprire
     * le domande dell'ablation:
     * - più parole che formano un concetto (prossimità): q01, q23, q25
     * - query che con AND trovano pochi articoli (AND contro OR): q04, q13, q20
     * - argomenti ampi (categorie nella ricerca, boost): q02, q10, q27
     * - persona: q08
     */
    private static final String[] QUERIES_TO_JUDGE = {
        "q01", "q02", "q04", "q08", "q10", "q13", "q20", "q23", "q25", "q27"
    };

    private static final Path OUTPUT = Path.of("target", "evaluation", "pool.tsv");

    @Autowired
    private SearchService searchService;

    @Test
    void buildPool() throws Exception {
        Map<String, String> queries = EvaluationData.readQueries();
        // voti già dati: vengono ricopiati nel nuovo pool
        Map<String, Map<String, Integer>> existing = EvaluationData.readJudgments(EvaluationData.qrelsPath());

        SearchSettings settings = searchService.getSettings();
        SearchSettings current = new SearchSettings();
        current.copyFrom(settings);

        // qid -> (id articolo -> riga del pool), senza duplicati
        Map<String, Map<String, PoolEntry>> pool = new LinkedHashMap<String, Map<String, PoolEntry>>();

        try {
            for (String qid : QUERIES_TO_JUDGE) {
                String text = queries.get(qid);
                if (text == null) {
                    throw new IllegalStateException("Query " + qid + " non presente in queries.tsv");
                }
                Map<String, PoolEntry> entries = new LinkedHashMap<String, PoolEntry>();
                pool.put(qid, entries);

                for (String name : AblationConfigurations.names()) {
                    settings.copyFrom(AblationConfigurations.create(name, current));
                    SearchResponse response = searchService.search(text, "", null, null, false, 1, POOL_DEPTH);

                    for (SearchResult result : response.results()) {
                        if (!entries.containsKey(result.id())) {
                            entries.put(result.id(), new PoolEntry(result.id(), result.title(), result.url()));
                        }
                    }
                }
            }
        } finally {
            // ripristina la configurazione iniziale anche in caso di errore
            settings.copyFrom(current);
        }

        int total = 0;
        int alreadyJudged = 0;

        Files.createDirectories(OUTPUT.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {
            writer.write("qid\tid\trelevance\ttitle\turl");
            writer.newLine();

            for (String qid : pool.keySet()) {
                List<PoolEntry> rows = new ArrayList<PoolEntry>(pool.get(qid).values());
                sortByTitle(rows);

                Map<String, Integer> oldGrades = existing.get(qid);
                for (PoolEntry row : rows) {
                    String relevance = "";
                    if (oldGrades != null && oldGrades.containsKey(row.id)) {
                        relevance = String.valueOf(oldGrades.get(row.id));
                        alreadyJudged++;
                    }
                    writer.write(qid + "\t" + row.id + "\t" + relevance + "\t"
                            + clean(row.title) + "\t" + clean(row.url));
                    writer.newLine();
                    total++;
                }
                System.out.println(qid + "  " + queries.get(qid) + "  -> " + rows.size() + " articoli da giudicare");
            }
        }

        System.out.println("Configurazioni unite: " + AblationConfigurations.names());
        System.out.println("Righe del pool: " + total + " (già giudicate: " + alreadyJudged
                + ", da giudicare: " + (total - alreadyJudged) + ")");
        System.out.println("Pool scritto in " + OUTPUT.toAbsolutePath());

        assertTrue(total > 0, "Il pool è vuoto");
    }

    /** Ordina le righe per titolo, ignorando maiuscole e minuscole. */
    private static void sortByTitle(List<PoolEntry> rows) {
        Collections.sort(rows, new Comparator<PoolEntry>() {
            @Override
            public int compare(PoolEntry a, PoolEntry b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
    }

    /** Toglie tabulazioni e a capo, che romperebbero le colonne del file TSV. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    /** Una riga del pool. */
    private static class PoolEntry {
        final String id;
        final String title;
        final String url;

        PoolEntry(String id, String title, String url) {
            this.id = id;
            if (title == null) {
                this.title = "";
            } else {
                this.title = title;
            }
            this.url = url;
        }
    }
}
