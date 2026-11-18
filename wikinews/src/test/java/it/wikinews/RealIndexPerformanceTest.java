package it.wikinews;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchService;

/**
 * Misura quanto ci mette SearchService a rispondere sull'indice REALE dell'applicazione.
 *
 * <p>Per ognuna delle 20 query: una prima esecuzione (tempo "iniziale"), poi 10 esecuzioni
 * misurate. Colonne stampate, in millisecondi:
 * <ul>
 *   <li><b>hit</b>: quanti articoli trova (0 = termine non presente, misura poco utile);</li>
 *   <li><b>prima</b>: durata della prima esecuzione (la primissima query include anche
 *       il riscaldamento della JVM);</li>
 *   <li><b>min / media / max</b>: tempi delle 10 esecuzioni successive.</li>
 * </ul>
 * In fondo: numero di ricerche, tempo medio complessivo, la più lenta e ricerche al secondo.
 * Si sono presi come esempi parole del dataset. In particolare si vede come in OR e AND si 
 * siano utilizzate le stesse, di cui in una caso si sa che non ci sono risultati
 */
@SpringBootTest
class RealIndexPerformanceTest {

    private static final int RUNS = 10;

    @Autowired
    private SearchService service;

    private final List<Double> allTimesMs = new ArrayList<>();

    @Test
    void twentyQueriesOnRealIndex() throws Exception {
        System.out.printf("%-26s %8s %9s %9s %9s %9s%n", "query", "hit", "prima", "min", "media", "max");

        // measure(etichetta, testo, categoria, dal, al, ordinaPerData)
        // Modifica liberamente termini e categorie: guarda la colonna "hit".

        // --- solo testo ---
        measure("termine singolo",   "scuola",                 "", null, null, false);
        measure("termine frequente", "europa",                  "", null, null, false);
        measure("altro termine",     "governo",                   "", null, null, false);
        measure("nome proprio",      "Roma",                      "", null, null, false);
        measure("due termini",       "incidente stradale",        "", null, null, false);
        measure("frase esatta",      "\"Decima giornata Super 10 2006-2007\"", "", null, null, false);
        measure("+ e -",             "+calcio -serie",            "", null, null, false);
        measure("campo categories",  "categories:sport",          "", null, null, false);
        measure("2 parole",          "scuola terremoto",    "", null, null, false);
        measure("2 parole in OR",    "scuola OR terremoto",    "", null, null, false);
        measure("2 parole inAND",    "scuola AND terremoto",        "", null, null, false);
        measure("wildcard",          "terrem*",                   "", null, null, false);
        measure("query lunga",       "il governo italiano ha annunciato nuove misure economiche", "", null, null, false);
        measure("nessun risultato",  "xyzzynonesistente",         "", null, null, false);

        // --- filtri ---
        measure("testo + categoria", "governo",  "Politica", null, null, false);
        measure("solo categoria",    "",         "Sport",    null, null, false);
        measure("testo + date",      "governo",  "", LocalDate.of(2015, 1, 1), LocalDate.of(2020, 12, 31), false);
        measure("solo date",         "",         "", LocalDate.of(2020, 1, 1), null, false);
        measure("testo+cat+date",    "elezioni", "Politica", LocalDate.of(2018, 1, 1), null, false);

        // --- ordinamento ---
        measure("ordinata per data", "elezioni", "", null, null, true);
        measure("tutti i documenti", "",         "", null, null, true);   // hit = dimensione dell'indice

        // ---- riepilogo ----
        double sum = allTimesMs.stream().mapToDouble(Double::doubleValue).sum();
        double max = allTimesMs.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double avg = sum / allTimesMs.size();
        System.out.println("\n--- RIEPILOGO ---");
        System.out.printf("Ricerche misurate: %d%n", allTimesMs.size());
        System.out.printf("Tempo medio: %.2f ms%n", avg);
        System.out.printf("Ricerca più lenta: %.2f ms%n", max);
        System.out.printf("Ricerche al secondo (un thread): %.0f%n", 1000 / avg);
    }

    /** Esegue una query una volta + RUNS volte e stampa una riga della tabella. */
    private void measure(String label, String text, String category,
                         LocalDate from, LocalDate to, boolean byDate) throws Exception {
        // prima esecuzione
        long t0 = System.nanoTime();
        SearchResponse first = service.search(text, category, from, to, byDate, 1, 10);
        double firstMs = (System.nanoTime() - t0) / 1e6;

        // esecuzioni misurate
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < RUNS; i++) {
            t0 = System.nanoTime();
            service.search(text, category, from, to, byDate, 1, 10);
            double ms = (System.nanoTime() - t0) / 1e6;
            min = Math.min(min, ms);
            max = Math.max(max, ms);
            sum += ms;
            allTimesMs.add(ms);
        }

        System.out.printf("%-26s %8d %9.2f %9.2f %9.2f %9.2f%n",
            label, first.totalHits(), firstMs, min, sum / RUNS, max);
    }
}