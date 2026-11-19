package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;
import it.wikinews.lucene.search.SearchSettings;

/**
 * Valutazione per argomento con i giudizi manuali (qrels.tsv).
 *
 * Per ogni configurazione dell'ablation (AblationConfigurations) esegue le query giudicate
 * e calcola, come media sulle query:
 * - P@10:       quanti dei primi 10 risultati sono rilevanti;
 * - Recall@100: quanti dei rilevanti conosciuti compaiono nei primi 100;
 * - MAP:        qualità di tutta la classifica (ordine e completezza);
 * - MRR:        posizione del primo risultato rilevante;
 * - nDCG@10:    come la precisione, ma con i voti 0/1/2 e pesando di più le prime posizioni.
 *
 * Rilevante = voto 1 o 2. Gli articoli mai giudicati contano come non rilevanti (regola standard
 * del pooling). Le query senza nessun articolo rilevante vengono saltate (tutte le metriche varrebbero 0).
 *
 * Se qrels.tsv non esiste il test viene saltato (non fallisce): prima vanno dati i giudizi.
 * Dettaglio per query e configurazione in target/evaluation/retrieval-results.tsv.
 *
 * Lancio:  ./mvnw test -Dtest=RetrievalEvaluationTest -DexcludedGroups=none
 */
@Tag("evaluation")
@SpringBootTest(properties = {
        "wikinews.data.maxArticle=20000",
        "wikinews.index.path=target/evaluation-index"
})
class RetrievalEvaluationTest {

    /** Quanti risultati guardare per Recall e MAP. */
    private static final int SEARCH_DEPTH = 100;

    private static final Path OUTPUT = Path.of("target", "evaluation", "retrieval-results.tsv");

    @Autowired
    private SearchService searchService;

    @Test
    void evaluateWithJudgments() throws Exception {
        Path qrelsFile = EvaluationData.qrelsPath();
        assumeTrue(Files.exists(qrelsFile),
                "Manca " + qrelsFile + ": generare il pool con PoolBuilderTest e giudicarlo");

        Map<String, String> queries = EvaluationData.readQueries();
        Map<String, Map<String, Integer>> judgments = EvaluationData.readJudgments(qrelsFile);

        // query valutabili: giudicate, presenti in queries.tsv e con almeno un rilevante
        List<String> qids = new ArrayList<String>();
        for (String qid : judgments.keySet()) {
            if (!queries.containsKey(qid)) {
                System.out.println("ATTENZIONE: " + qid + " è in qrels.tsv ma non in queries.tsv, saltata");
            } else if (relevantIds(judgments.get(qid)).isEmpty()) {
                System.out.println("ATTENZIONE: " + qid + " non ha articoli rilevanti, saltata");
            } else {
                qids.add(qid);
            }
        }
        assumeTrue(!qids.isEmpty(), "Nessuna query con giudizi utilizzabili in " + qrelsFile);

        SearchSettings settings = searchService.getSettings();
        SearchSettings current = new SearchSettings();
        current.copyFrom(settings);

        List<String> summaryLines = new ArrayList<String>();

        Files.createDirectories(OUTPUT.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8)) {
            writer.write("configuration\tqid\tquery\trelevant\tP@10\tRecall@100\tAP\tRR\tnDCG@10");
            writer.newLine();

            for (String name : AblationConfigurations.names()) {
                settings.copyFrom(AblationConfigurations.create(name, current));

                double sumP10 = 0.0;
                double sumRecall = 0.0;
                double sumAp = 0.0;
                double sumRr = 0.0;
                double sumNdcg = 0.0;

                for (String qid : qids) {
                    Map<String, Integer> grades = judgments.get(qid);
                    Set<String> relevant = relevantIds(grades);
                    List<String> ranking = runQuery(queries.get(qid));

                    double p10 = IrMetrics.precisionAtK(ranking, relevant, 10);
                    double recall = IrMetrics.recallAtK(ranking, relevant, SEARCH_DEPTH);
                    double ap = IrMetrics.averagePrecision(ranking, relevant);
                    double rr = IrMetrics.reciprocalRank(ranking, relevant);
                    double ndcg = IrMetrics.ndcgAtK(ranking, grades, 10);

                    sumP10 = sumP10 + p10;
                    sumRecall = sumRecall + recall;
                    sumAp = sumAp + ap;
                    sumRr = sumRr + rr;
                    sumNdcg = sumNdcg + ndcg;

                    writer.write(name + "\t" + qid + "\t" + queries.get(qid) + "\t" + relevant.size() + "\t"
                            + format(p10) + "\t" + format(recall) + "\t" + format(ap) + "\t"
                            + format(rr) + "\t" + format(ndcg));
                    writer.newLine();
                }

                int n = qids.size();
                summaryLines.add(String.format("%-20s %7.3f %11.3f %7.3f %7.3f %9.3f",
                        name, sumP10 / n, sumRecall / n, sumAp / n, sumRr / n, sumNdcg / n));
            }
        } finally {
            // ripristina la configurazione iniziale
            settings.copyFrom(current);
        }

        System.out.println();
        System.out.println("=== VALUTAZIONE PER ARGOMENTO: " + qids.size() + " query giudicate " + qids + " ===");
        System.out.println("Configurazione attuale: " + current);
        System.out.println(String.format("%-20s %7s %11s %7s %7s %9s",
                "configurazione", "P@10", "Recall@100", "MAP", "MRR", "nDCG@10"));
        for (String line : summaryLines) {
            System.out.println(line);
        }
        System.out.println("Dettaglio per query: " + OUTPUT.toAbsolutePath());
    }

    /** Esegue la query e restituisce gli id dei primi SEARCH_DEPTH risultati, in ordine. */
    private List<String> runQuery(String text) {
        List<String> ranking = new ArrayList<String>();
        try {
            SearchResponse response = searchService.search(text, "", null, null, false, 1, SEARCH_DEPTH);
            for (SearchResult result : response.results()) {
                ranking.add(result.id());
            }
        } catch (Exception e) {
            // query non valida per il parser: nessun risultato
            System.out.println("Errore nella query [" + text + "]: " + e.getMessage());
        }
        return ranking;
    }

    /** Gli articoli con voto 1 o 2. */
    private static Set<String> relevantIds(Map<String, Integer> grades) {
        Set<String> relevant = new HashSet<String>();
        for (String id : grades.keySet()) {
            if (grades.get(id) >= 1) {
                relevant.add(id);
            }
        }
        return relevant;
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }
}
