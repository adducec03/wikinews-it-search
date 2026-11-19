package it.wikinews.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica le formule di IrMetrics su esempi piccoli calcolati a mano.
 * Se questi test passano, i numeri della valutazione del motore sono affidabili.
 *
 * Negli esempi le lettere sono id di articoli: a, b rilevanti; x, y, z non rilevanti.
 */
@DisplayName("Metriche IR: formule verificate su esempi calcolati a mano")
class IrMetricsTest {

    /** Tolleranza nei confronti tra numeri decimali. */
    private static final double DELTA = 0.0001;

    @Test
    @DisplayName("Precision@K")
    void precisionAtK() {
        List<String> ranking = List.of("a", "x", "b", "y");
        Set<String> relevant = Set.of("a", "b");

        // primi 2: a (sì), x (no) -> 1/2
        assertEquals(0.5, IrMetrics.precisionAtK(ranking, relevant, 2), DELTA);
        // primi 4: a, b rilevanti -> 2/4
        assertEquals(0.5, IrMetrics.precisionAtK(ranking, relevant, 4), DELTA);
        // primo: a -> 1/1
        assertEquals(1.0, IrMetrics.precisionAtK(ranking, relevant, 1), DELTA);
    }

    @Test
    @DisplayName("Precision@K con meno di K risultati: le posizioni vuote contano come non rilevanti")
    void precisionAtKWithFewResults() {
        List<String> ranking = List.of("a");
        Set<String> relevant = Set.of("a");

        // 1 rilevante, ma si divide per 10
        assertEquals(0.1, IrMetrics.precisionAtK(ranking, relevant, 10), DELTA);
    }

    @Test
    @DisplayName("Recall@K")
    void recallAtK() {
        List<String> ranking = List.of("a", "x", "b", "y");
        Set<String> relevant = Set.of("a", "b", "c");

        // nei primi 2 c'è solo a: 1 rilevante su 3
        assertEquals(1.0 / 3, IrMetrics.recallAtK(ranking, relevant, 2), DELTA);
        // nei primi 4 ci sono a e b: 2 su 3 (c non viene mai trovato)
        assertEquals(2.0 / 3, IrMetrics.recallAtK(ranking, relevant, 4), DELTA);
    }

    @Test
    @DisplayName("Reciprocal Rank: posizione del primo rilevante")
    void reciprocalRank() {
        Set<String> relevant = Set.of("a");

        assertEquals(1.0, IrMetrics.reciprocalRank(List.of("a", "x"), relevant), DELTA);
        assertEquals(0.5, IrMetrics.reciprocalRank(List.of("x", "a"), relevant), DELTA);
        assertEquals(1.0 / 3, IrMetrics.reciprocalRank(List.of("x", "y", "a"), relevant), DELTA);
        // nessun rilevante trovato
        assertEquals(0.0, IrMetrics.reciprocalRank(List.of("x", "y"), relevant), DELTA);
    }

    @Test
    @DisplayName("Average Precision")
    void averagePrecision() {
        List<String> ranking = List.of("a", "x", "b", "y");
        Set<String> relevant = Set.of("a", "b");

        // a in posizione 1 -> precisione 1/1; b in posizione 3 -> precisione 2/3
        // AP = (1 + 2/3) / 2 = 0.8333
        double expected = (1.0 + 2.0 / 3) / 2;
        assertEquals(expected, IrMetrics.averagePrecision(ranking, relevant), DELTA);
    }

    @Test
    @DisplayName("Average Precision: i rilevanti non trovati abbassano il punteggio")
    void averagePrecisionWithMissingRelevant() {
        // a trovato in posizione 1, b mai trovato -> (1 + 0) / 2
        assertEquals(0.5, IrMetrics.averagePrecision(List.of("a", "x"), Set.of("a", "b")), DELTA);
        // nessun rilevante trovato
        assertEquals(0.0, IrMetrics.averagePrecision(List.of("x", "y"), Set.of("a")), DELTA);
        // query senza rilevanti giudicati
        assertEquals(0.0, IrMetrics.averagePrecision(List.of("a"), Set.of()), DELTA);
    }

    @Test
    @DisplayName("nDCG@K con voti graduati")
    void ndcgAtK() {
        Map<String, Integer> grades = new HashMap<String, Integer>();
        grades.put("a", 2);   // molto rilevante
        grades.put("b", 1);   // in parte rilevante
        grades.put("x", 0);   // giudicato non rilevante

        // ranking ideale: prima a (voto 2) poi b (voto 1) -> nDCG = 1
        assertEquals(1.0, IrMetrics.ndcgAtK(List.of("a", "b"), grades, 10), DELTA);

        // ordine invertito:
        // DCG    = (2^1-1)/log2(2) + (2^2-1)/log2(3) = 1 + 1.8928 = 2.8928
        // DCG id = (2^2-1)/log2(2) + (2^1-1)/log2(3) = 3 + 0.6309 = 3.6309
        assertEquals(0.7967, IrMetrics.ndcgAtK(List.of("b", "a"), grades, 10), DELTA);

        // un articolo non giudicato (z) in testa conta come non rilevante:
        // DCG = 0 + 3/log2(3) + 1/log2(4) = 1.8928 + 0.5 = 2.3928 -> 2.3928 / 3.6309
        assertEquals(0.6590, IrMetrics.ndcgAtK(List.of("z", "a", "b"), grades, 10), DELTA);
    }

    @Test
    @DisplayName("nDCG@K senza articoli rilevanti vale 0")
    void ndcgWithoutRelevant() {
        Map<String, Integer> grades = new HashMap<String, Integer>();
        grades.put("x", 0);

        assertEquals(0.0, IrMetrics.ndcgAtK(List.of("x", "y"), grades, 10), DELTA);
    }
}
