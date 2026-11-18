package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;

/**
 * Prepara il "pool" di articoli da giudicare a mano (tecnica del pooling).
 *
 * Per ogni query di evaluation/queries.tsv prende i primi POOL_DEPTH risultati del motore
 * e li scrive in target/evaluation/pool.tsv con la colonna "relevance" vuota.
 * Chi giudica scrive in quella colonna:
 *   0 = non rilevante, 1 = in parte rilevante, 2 = molto rilevante
 * leggendo il bisogno informativo ("need") della query in queries.tsv.
 * Il file giudicato diventa src/test/resources/evaluation/qrels.tsv.
 *
 * Usa l'indice COMPLETO: all'avvio l'applicazione indicizza tutto il dump in una
 * cartella separata (target/evaluation-index), senza toccare l'indice di sviluppo.
 *
 * Lancio:  ./mvnw test -Dtest=PoolBuilderTest -DexcludedGroups=none
 */
@Tag("evaluation")
@SpringBootTest(properties = {
        "wikinews.data.maxArticle=20000",
        "wikinews.index.path=target/evaluation-index"
})
class PoolBuilderTest {

    /** Quanti risultati per query mettere nel pool. */
    private static final int POOL_DEPTH = 20;

    private static final Path OUTPUT = Path.of("target", "evaluation", "pool.tsv");

    @Autowired
    private SearchService searchService;

    @Test
    void buildPool() throws Exception {
        List<String[]> queries = readQueries();
        assertFalse(queries.isEmpty(), "queries.tsv non contiene query");

        Files.createDirectories(OUTPUT.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {
            writer.write("qid\tid\trelevance\trank\ttitle\turl");
            writer.newLine();

            for (String[] query : queries) {
                String qid = query[0];
                String text = query[1];

                SearchResponse response = searchService.search(text, "", null, null, false, 1, POOL_DEPTH);
                System.out.println(qid + "  " + text + "  -> " + response.totalHits() + " risultati");

                int rank = 1;
                for (SearchResult result : response.results()) {
                    writer.write(qid + "\t"
                            + result.id() + "\t"
                            + "" + "\t"                  // relevance: da compilare a mano
                            + rank + "\t"
                            + clean(result.title()) + "\t"
                            + clean(result.url()));
                    writer.newLine();
                    rank++;
                }
            }
        }

        System.out.println("Pool scritto in " + OUTPUT.toAbsolutePath());
    }

    /** Legge queries.tsv dal classpath: ogni riga è qid, query, need (la prima riga è l'intestazione). */
    private List<String[]> readQueries() throws IOException {
        List<String[]> queries = new ArrayList<String[]>();

        InputStream input = getClass().getResourceAsStream("/evaluation/queries.tsv");
        if (input == null) {
            throw new IOException("File evaluation/queries.tsv non trovato in src/test/resources");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // intestazione: la saltiamo
            line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    String[] columns = line.split("\t");
                    queries.add(columns);
                }
                line = reader.readLine();
            }
        }
        return queries;
    }

    /** Toglie tabulazioni e a capo, che romperebbero le colonne del file TSV. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }
}
