package it.wikinews.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metriche di Information Retrieval per valutare la qualità dei risultati
 *
 * Tutti i metodi ricevono:
 * - ranking:  gli id degli articoli restituiti dal motore, nell'ordine in cui li mostra
 *             (posizione 0 = primo risultato);
 * - relevant: gli id degli articoli giudicati rilevanti per quella query (rilevanza >= 1),
 *   oppure grades: per ogni id giudicato, il voto 0 (non rilevante), 1 (in parte), 2 (molto).
 *
 * Le medie su tutte le query si calcolano fuori da questa classe:
 * MAP = media degli averagePrecision, MRR = media dei reciprocalRank.
 */
public final class IrMetrics {

    private IrMetrics() {
        // classe di soli metodi statici: non va istanziata
    }

    /**
     * Precision@K: quanti dei primi K risultati sono rilevanti, diviso K.
     * Si divide sempre per K: se il motore restituisce meno di K risultati,
     * le posizioni vuote contano come "non rilevanti".
     */
    public static double precisionAtK(List<String> ranking, Set<String> relevant, int k) {
        if (k <= 0) {
            return 0.0;
        }

        int found = 0;
        for (int i = 0; i < k && i < ranking.size(); i++) {
            if (relevant.contains(ranking.get(i))) {
                found++;
            }
        }
        return (double) found / k;
    }

    /**
     * Recall@K: quanti dei rilevanti compaiono nei primi K risultati,
     * diviso il numero totale di rilevanti.
     */
    public static double recallAtK(List<String> ranking, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }

        int found = 0;
        for (int i = 0; i < k && i < ranking.size(); i++) {
            if (relevant.contains(ranking.get(i))) {
                found++;
            }
        }
        return (double) found / relevant.size();
    }

    /**
     * Reciprocal Rank: 1 / posizione del primo risultato rilevante
     * (1 se è il primo, 0.5 se è il secondo, ...). 0 se non ce n'è nessuno.
     */
    public static double reciprocalRank(List<String> ranking, Set<String> relevant) {
        for (int i = 0; i < ranking.size(); i++) {
            if (relevant.contains(ranking.get(i))) {
                int position = i + 1;
                return 1.0 / position;
            }
        }
        return 0.0;
    }

    /**
     * Average Precision: ogni volta che nel ranking compare un rilevante si calcola
     * la precisione fino a quel punto; poi si fa la media.
     * Si divide per il numero TOTALE di rilevanti: quelli che il motore non trova
     * contano come precisione 0, quindi l'AP premia sia l'ordine sia la completezza.
     */
    public static double averagePrecision(List<String> ranking, Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 0.0;
        }

        int found = 0;
        double sum = 0.0;
        for (int i = 0; i < ranking.size(); i++) {
            if (relevant.contains(ranking.get(i))) {
                found++;
                int position = i + 1;
                double precisionHere = (double) found / position;
                sum = sum + precisionHere;
            }
        }
        return sum / relevant.size();
    }

    /**
     * nDCG@K (normalized Discounted Cumulative Gain).
     * Usa i voti graduati: un articolo con voto 2 vale più di uno con voto 1,
     * e un risultato vale meno quanto più è in basso (diviso log2(posizione + 1)).
     * Il valore viene poi diviso per quello del ranking ideale (voti in ordine decrescente),
     * così il risultato è sempre tra 0 e 1.
     */
    public static double ndcgAtK(List<String> ranking, Map<String, Integer> grades, int k) {
        // DCG del ranking prodotto dal motore
        List<Integer> obtainedGrades = new ArrayList<Integer>();
        for (int i = 0; i < k && i < ranking.size(); i++) {
            Integer grade = grades.get(ranking.get(i));
            if (grade == null) {
                // articolo non giudicato: lo consideriamo non rilevante
                obtainedGrades.add(0);
            } else {
                obtainedGrades.add(grade);
            }
        }
        double dcg = dcg(obtainedGrades, k);

        // DCG ideale: tutti i voti assegnati, dal più alto al più basso
        List<Integer> idealGrades = new ArrayList<Integer>(grades.values());
        Collections.sort(idealGrades, Collections.reverseOrder());
        double idealDcg = dcg(idealGrades, k);

        if (idealDcg == 0.0) {
            // nessun articolo rilevante per questa query
            return 0.0;
        }
        return dcg / idealDcg;
    }

    /** DCG dei primi k voti: somma di (2^voto - 1) / log2(posizione + 1). */
    private static double dcg(List<Integer> gradesInOrder, int k) {
        double sum = 0.0;
        for (int i = 0; i < k && i < gradesInOrder.size(); i++) {
            int grade = gradesInOrder.get(i);
            if (grade > 0) {
                int position = i + 1;
                double gain = Math.pow(2, grade) - 1;
                double discount = log2(position + 1);
                sum = sum + gain / discount;
            }
        }
        return sum;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
