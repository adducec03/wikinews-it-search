package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.lucene.index.IndexFields;
import it.wikinews.lucene.search.ArticleSearcher;
import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;

/**
 * Valutazione automatica "known-item": ogni articolo cerca se stesso.
 *
 * Si scelgono a caso SAMPLE_SIZE articoli dell'indice. Per ognuno si costruisce una query
 * a partire dall'articolo e l'unico risultato giusto è l'articolo stesso: non servono
 * giudizi manuali. Si misura in che posizione compare.
 *
 * Due tipi di query:
 * - "titolo":           il titolo dell'articolo (l'utente conosce il titolo);
 * - "parole del testo": WORDS_PER_QUERY parole prese dall'abstract (o dal testo) e NON
 *                       presenti nel titolo (l'utente ricorda di cosa parlava, non il titolo).
 *
 * Metriche (calcolate con IrMetrics, con un solo articolo rilevante per query):
 * - MRR:        media di 1/posizione dell'articolo (0 se non trovato nei primi SEARCH_DEPTH);
 * - Success@1:  quota di query in cui l'articolo è il primo risultato;
 * - Success@10: quota di query in cui l'articolo è in prima pagina.
 *
 * Il dettaglio di ogni query è in target/evaluation/known-item.tsv (utile per l'analisi degli errori).
 * Il seme casuale è fisso: ogni esecuzione usa gli stessi articoli e le stesse query,
 * quindi i numeri sono confrontabili tra configurazioni diverse (ablation study).
 *
 * Lancio:  ./mvnw test -Dtest=KnownItemEvaluationTest -DexcludedGroups=none
 */
@Tag("evaluation")
@SpringBootTest(properties = {
        "wikinews.data.maxArticle=20000",
        "wikinews.index.path=target/evaluation-index"
})
class KnownItemEvaluationTest {

    /** Quanti articoli usare come query. */
    private static final int SAMPLE_SIZE = 500;

    /** Seme del generatore casuale: fisso, così il campione è sempre lo stesso. */
    private static final long SEED = 42;

    /** Quanti risultati guardare: oltre questa posizione l'articolo conta come "non trovato". */
    private static final int SEARCH_DEPTH = 100;

    /** Parole per le query costruite dal testo. */
    private static final int WORDS_PER_QUERY = 4;

    /** Lunghezza minima delle parole scelte (evita articoli, preposizioni, ecc.). */
    private static final int MIN_WORD_LENGTH = 5;

    /** Quanti esempi di query fallite stampare per ogni tipo. */
    private static final int FAILURES_TO_PRINT = 5;

    private static final Path OUTPUT = Path.of("target", "evaluation", "known-item.tsv");

    @Autowired
    private SearchService searchService;

    @Autowired
    private ArticleSearcher articleSearcher;

    @Test
    void knownItemSearch() throws Exception {
        List<Document> sample = sampleArticles();
        assertTrue(sample.size() > 0, "Indice vuoto: nessun articolo da usare come query");

        // generatore separato per le parole, con lo stesso seme: risultati ripetibili
        Random random = new Random(SEED);
        VariantResult titleResult = new VariantResult("titolo");
        VariantResult textResult = new VariantResult("parole del testo");

        Files.createDirectories(OUTPUT.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {
            writer.write("variant\tid\trank\tquery\ttitle");
            writer.newLine();

            for (Document doc : sample) {
                String id = doc.get(IndexFields.ID);
                String title = doc.get(IndexFields.NAME);

                // 1) query = titolo. escape: i caratteri speciali di Lucene (: - " ecc.)
                //    vengono cercati come testo normale e non interpretati come sintassi
                String titleQuery = QueryParser.escape(title.toLowerCase());
                evaluate(titleResult, id, title, titleQuery, writer);

                // 2) query = parole del testo che non stanno nel titolo
                String textQuery = buildTextQuery(doc, random);
                if (textQuery != null) {
                    evaluate(textResult, id, title, textQuery, writer);
                }
            }
        }

        System.out.println();
        System.out.println("=== KNOWN-ITEM: " + sample.size() + " articoli, risultati considerati: " + SEARCH_DEPTH + " ===");
        titleResult.print();
        textResult.print();
        System.out.println("Dettaglio per query: " + OUTPUT.toAbsolutePath());

        assertTrue(titleResult.queries > 0, "Nessuna query eseguita");
    }

    /** Esegue una query, trova la posizione dell'articolo cercato e aggiorna le metriche. */
    private void evaluate(VariantResult result, String targetId, String title, String query,
                          BufferedWriter writer) throws Exception {
        List<String> ranking = new ArrayList<String>();
        boolean error = false;

        try {
            SearchResponse response = searchService.search(query, "", null, null, false, 1, SEARCH_DEPTH);
            for (SearchResult r : response.results()) {
                ranking.add(r.id());
            }
        } catch (Exception e) {
            // query che il parser non accetta: conta come "non trovato"
            error = true;
        }

        // posizione dell'articolo cercato (1 = primo); 0 = non trovato
        int rank = ranking.indexOf(targetId) + 1;
        result.add(ranking, targetId, rank, error, query, title);

        writer.write(result.name + "\t" + targetId + "\t" + rank + "\t" + clean(query) + "\t" + clean(title));
        writer.newLine();
    }

    /**
     * Sceglie a caso SAMPLE_SIZE articoli dell'indice (sempre gli stessi, grazie al seme fisso).
     * Salta i documenti cancellati (es. duplicati sostituiti) e quelli senza titolo.
     */
    private List<Document> sampleArticles() throws Exception {
        List<Document> sample = new ArrayList<Document>();
        IndexSearcher searcher = articleSearcher.acquireSearcher();
        try {
            IndexReader reader = searcher.getIndexReader();
            StoredFields storedFields = searcher.storedFields();
            // liveDocs dice quali documenti sono ancora validi (null = nessuno cancellato)
            Bits liveDocs = MultiBits.getLiveDocs(reader);

            // tutti i docId validi, poi mescolati con il seme fisso
            List<Integer> docIds = new ArrayList<Integer>();
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (liveDocs == null || liveDocs.get(i)) {
                    docIds.add(i);
                }
            }
            Collections.shuffle(docIds, new Random(SEED));

            for (int i = 0; i < docIds.size() && sample.size() < SAMPLE_SIZE; i++) {
                Document doc = storedFields.document(docIds.get(i));
                String title = doc.get(IndexFields.NAME);
                if (title != null && !title.isBlank()) {
                    sample.add(doc);
                }
            }
        } finally {
            articleSearcher.releaseSearcher(searcher);
        }
        return sample;
    }

    /**
     * Costruisce una query con WORDS_PER_QUERY parole scelte a caso dall'abstract
     * (o dal testo, se l'abstract è vuoto), escludendo le parole del titolo.
     * Restituisce null se l'articolo non ha abbastanza parole adatte.
     */
    private String buildTextQuery(Document doc, Random random) {
        String source = doc.get(IndexFields.ABSTRACT);
        if (source == null || source.isBlank()) {
            source = doc.get(IndexFields.CONTENT);
        }
        if (source == null) {
            return null;
        }

        Set<String> titleWords = new HashSet<String>();
        String[] titleParts = doc.get(IndexFields.NAME).toLowerCase().split("[^\\p{L}]+");
        for (String part : titleParts) {
            titleWords.add(part);
        }

        // parole candidate: solo lettere, abbastanza lunghe, non nel titolo, senza ripetizioni
        List<String> candidates = new ArrayList<String>();
        String[] words = source.toLowerCase().split("[^\\p{L}]+");
        for (String word : words) {
            if (word.length() >= MIN_WORD_LENGTH && !titleWords.contains(word) && !candidates.contains(word)) {
                candidates.add(word);
            }
        }

        if (candidates.size() < WORDS_PER_QUERY) {
            return null;
        }

        Collections.shuffle(candidates, random);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < WORDS_PER_QUERY; i++) {
            if (i > 0) {
                query.append(" ");
            }
            query.append(candidates.get(i));
        }
        return query.toString();
    }

    /** Toglie tabulazioni e a capo, che romperebbero le colonne del file TSV. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    /** Somma le metriche di un tipo di query e le stampa alla fine. */
    private static class VariantResult {
        final String name;
        int queries = 0;
        int notFound = 0;
        int errors = 0;
        double sumReciprocalRank = 0.0;
        double sumSuccessAt1 = 0.0;
        double sumSuccessAt10 = 0.0;
        List<String> failures = new ArrayList<String>();

        VariantResult(String name) {
            this.name = name;
        }

        void add(List<String> ranking, String targetId, int rank, boolean error, String query, String title) {
            Set<String> relevant = new HashSet<String>();
            relevant.add(targetId);

            queries++;
            sumReciprocalRank = sumReciprocalRank + IrMetrics.reciprocalRank(ranking, relevant);
            // con un solo rilevante, Recall@K vale 1 se l'articolo è nei primi K, altrimenti 0
            sumSuccessAt1 = sumSuccessAt1 + IrMetrics.recallAtK(ranking, relevant, 1);
            sumSuccessAt10 = sumSuccessAt10 + IrMetrics.recallAtK(ranking, relevant, 10);

            if (error) {
                errors++;
            }
            if (rank == 0) {
                notFound++;
                if (failures.size() < FAILURES_TO_PRINT) {
                    failures.add("query: [" + query + "]  articolo: " + title);
                }
            }
        }

        void print() {
            System.out.println();
            System.out.println("--- query: " + name + " (" + queries + " query) ---");
            System.out.printf("MRR:          %.3f%n", sumReciprocalRank / queries);
            System.out.printf("Success@1:    %.3f%n", sumSuccessAt1 / queries);
            System.out.printf("Success@10:   %.3f%n", sumSuccessAt10 / queries);
            System.out.printf("Non trovati:  %d (di cui errori di parsing: %d)%n", notFound, errors);
            if (!failures.isEmpty()) {
                System.out.println("Esempi di articoli non trovati:");
                for (String failure : failures) {
                    System.out.println("  " + failure);
                }
            }
        }
    }
}
