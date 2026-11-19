package it.wikinews;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.index.IndexFields;
import it.wikinews.lucene.search.ArticleSearcher;
import it.wikinews.lucene.search.QuerySuggestionService;
import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;

/**
 * Test sul sistema REALE (indice vero di Wikinews).
 *
 * <p>Tre gruppi di test:
 * <ul>
 *   <li><b>A6 - Invarianti</b>: relazioni che devono valere sempre tra i risultati di query diverse
 *       (es. aggiungere un filtro non puo' aumentare i risultati). Usano SearchService reale.</li>
 *   <li><b>B - Qualita' del ranking</b>: "known-item search". Si prendono articoli a caso, si usa il
 *       loro titolo (o le prime parole dell'abstract) come query e si misura in che posizione
 *       ricompare l'articolo di partenza (Success@k e MRR).</li>
 *   <li><b>C - Ablazioni</b>: si cambia UNA scelta di progetto alla volta (boost, campi, operatore,
 *       similarity, parametri BM25, analyzer) e si confrontano le stesse metriche.</li>
 * </ul>
 *
 * <p>Query di riferimento: "europa" (molto frequente) e "scuola" (pochi risultati).
 *
 * <p><b>Configurazione</b>: indica il percorso dell'indice con la costante INDEX_PATH oppure
 * con {@code -Dwikinews.index=/percorso/indice}. Se l'indice non esiste i test vengono saltati.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Wikinews Search - test sul sistema reale (A6 invarianti, B ranking, C ablazioni)")
class PerformanceTest {

    // ==================================================================
    // CONFIGURAZIONE (modifica qui)
    // ==================================================================

    /** Cartella dell'indice Lucene reale. */
    private static final String INDEX_PATH = System.getProperty("wikinews.index", "index");

    /** Query di riferimento: una molto frequente e una rara. */
    private static final String FREQUENT = "europa";
    private static final String RARE = "scuola";

    /** Quanti articoli a caso usare per il known-item search (seme fisso = risultati ripetibili). */
    private static final int SAMPLE_SIZE = 200;
    private static final long SEED = 42;

    /** Quante parole dell'abstract usare come seconda query. */
    private static final int ABSTRACT_WORDS = 8;

    /** Imposta a false per saltare l'ablazione sull'analyzer (richiede di reindicizzare tutto in memoria). */
    private static final boolean RUN_ANALYZER_ABLATION = true;

    private static final String[] ALL_FIELDS = {
        IndexFields.NAME, IndexFields.ABSTRACT, IndexFields.CONTENT, IndexFields.CATEGORIES };

    /** Boost usati da QueryFactory.buildTextQuery(). */
    private static final Map<String, Float> BASE_BOOSTS = Map.of(
        IndexFields.NAME, 3f, IndexFields.ABSTRACT, 2f, IndexFields.CONTENT, 1f, IndexFields.CATEGORIES, 1f);

    // ==================================================================
    // STATO CONDIVISO
    // ==================================================================

    private Directory dir;
    private SearcherManager manager;
    private SearchService service;          // il servizio REALE dell'applicazione
    private IndexSearcher base;             // searcher sull'indice reale (per le ablazioni lato query)
    private Analyzer analyzer;              // analyzer reale (AnalyzerFactory)
    private List<Target> targets = new ArrayList<>();
    private final List<AutoCloseable> toClose = new ArrayList<>();

    private final Map<String, String> summary = new LinkedHashMap<>();
    private final Map<String, Long> durationsMs = new LinkedHashMap<>();
    private long startNanos;

    /** Un articolo scelto a caso per il known-item search. */
    private record Target(String url, String title, String abstr) { }

    /** Una configurazione di ricerca da confrontare (usata nelle ablazioni). */
    private record Variant(String name, IndexSearcher searcher, Analyzer analyzer,
                           String[] fields, Map<String, Float> boosts, boolean andOperator) {

        /** Costruisce la query di testo come fa QueryFactory, ma con i parametri della variante. */
        Query query(String text) throws ParseException {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);
            if (andOperator) {
                parser.setDefaultOperator(QueryParser.Operator.AND);
            }
            return parser.parse(text);
        }
    }

    /** Metriche del known-item search. */
    private record Metrics(int n, double s1, double s5, double s10, double mrr, double msPerQuery) { }

    /** Risultato di una variante (titolo e abstract) per il riepilogo. */
    private record Result(String name, Metrics title, Metrics abstr) { }

    // ==================================================================
    // SETUP / TEARDOWN
    // ==================================================================

    @BeforeAll
    void setUp() throws IOException {
        Path path = Paths.get(INDEX_PATH);
        assumeTrue(Files.isDirectory(path),
            "Indice non trovato in '" + path.toAbsolutePath() + "': imposta INDEX_PATH o -Dwikinews.index");

        // Apriamo l'indice come farebbe l'applicazione: SearcherManager -> ArticleSearcher -> SearchService
        dir = FSDirectory.open(path);
        manager = new SearcherManager(dir, null);
        service = new SearchService(new ArticleSearcher(manager), new QuerySuggestionService());
        base = manager.acquire();
        analyzer = AnalyzerFactory.createAnalyzer();
        buildTargets();

        System.out.println("\n================ WIKINEWS - TEST SUL SISTEMA REALE ================");
    }

    @AfterAll
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
        if (manager != null) {
            manager.release(base);
            manager.close();
        }
        if (analyzer != null) {
            analyzer.close();
        }
        if (dir != null) {
            dir.close();
        }
        printSummary();
    }

    @BeforeEach
    void startTimer() {
        startNanos = System.nanoTime();
    }

    @AfterEach
    void stopTimer(TestInfo info) {
        durationsMs.put(info.getDisplayName(), (System.nanoTime() - startNanos) / 1_000_000);
    }

    /** Sceglie a caso SAMPLE_SIZE articoli (con titolo, abstract e url) da usare come "known item". */
    private void buildTargets() throws IOException {
        IndexReader reader = base.getIndexReader();
        Bits live = MultiBits.getLiveDocs(reader);          // null se non ci sono cancellazioni
        StoredFields stored = reader.storedFields();
        List<Target> candidates = new ArrayList<>();
        for (int i = 0; i < reader.maxDoc(); i++) {
            if (live != null && !live.get(i)) {
                continue;
            }
            Document d = stored.document(i, Set.of(IndexFields.NAME, IndexFields.ABSTRACT, IndexFields.URL));
            String title = d.get(IndexFields.NAME);
            String abstr = d.get(IndexFields.ABSTRACT);
            String url = d.get(IndexFields.URL);
            if (title != null && !title.isBlank() && abstr != null && !abstr.isBlank() && url != null) {
                candidates.add(new Target(url, title, abstr));
            }
        }
        Collections.shuffle(candidates, new Random(SEED));
        targets = new ArrayList<>(candidates.subList(0, Math.min(SAMPLE_SIZE, candidates.size())));
        log("Articoli candidati (con titolo, abstract e url): %d -> campione: %d", candidates.size(), targets.size());
    }

    // ==================================================================
    // 0. DATI
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("01 Dati: dimensione indice e query di riferimento")
    void dataset() throws Exception {
        step("Dimensione dell'indice e frequenza delle query di riferimento");
        long frequent = hits(FREQUENT);
        long rare = hits(RARE);
        log("Documenti nell'indice: %d", base.getIndexReader().numDocs());
        log("'%s' -> %d risultati (query frequente)", FREQUENT, frequent);
        log("'%s' -> %d risultati (query rara)", RARE, rare);

        assertTrue(rare > 0, "'" + RARE + "' dovrebbe avere almeno un risultato");
        assertTrue(frequent > rare, "'" + FREQUENT + "' dovrebbe essere piu' frequente di '" + RARE + "'");
        summary.put("01 Dati: dimensione indice e query di riferimento",
            base.getIndexReader().numDocs() + " doc, " + FREQUENT + "=" + frequent + ", " + RARE + "=" + rare);
    }

    // ==================================================================
    // A6. INVARIANTI (SearchService reale)
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("02 Invarianti: determinismo e maiuscole/minuscole")
    void determinismAndCase() throws Exception {
        step("Invarianti: stessa query = stessi risultati; maiuscole/minuscole irrilevanti");

        SearchResponse r1 = run(FREQUENT, "", null, null, false, 1, 10);
        SearchResponse r2 = run(FREQUENT, "", null, null, false, 1, 10);
        long lower = hits(FREQUENT);
        long upper = hits(FREQUENT.toUpperCase());
        long capital = hits(Character.toUpperCase(FREQUENT.charAt(0)) + FREQUENT.substring(1));
        // il totale non dipende ne' dalla pagina ne' dalla dimensione pagina
        long otherPage = hits(run(FREQUENT, "", null, null, false, 3, 5));

        log("Due esecuzioni uguali: %s", urls(r1).equals(urls(r2)));
        log("'%s'=%d, '%s'=%d, '%s'=%d", FREQUENT, lower, FREQUENT.toUpperCase(), upper,
            Character.toUpperCase(FREQUENT.charAt(0)) + FREQUENT.substring(1), capital);
        log("totalHits da pagina 3 con 5 per pagina: %d", otherPage);

        assertAll(
            () -> assertEquals(urls(r1), urls(r2), "stessa query, stesso ordine"),
            () -> assertEquals(hits(r1), hits(r2)),
            () -> assertEquals(lower, upper, "maiuscole irrilevanti"),
            () -> assertEquals(lower, capital),
            () -> assertEquals(lower, otherPage, "totalHits non dipende da pagina e pageSize")
        );
        summary.put("02 Invarianti: determinismo e maiuscole/minuscole", "ok, totalHits=" + lower);
    }

    @Test
    @Order(3)
    @DisplayName("03 Invarianti: i filtri non aumentano i risultati")
    void filtersNeverIncreaseHits() throws Exception {
        step("Invarianti: aggiungere un filtro non puo' aumentare i risultati");

        SearchResponse all = run(FREQUENT, "", null, null, false, 1, 10);
        SearchResult first = results(all).get(0);
        String firstUrl = str(first, "url");
        long total = hits(all);

        // Categoria: prendo la prima categoria del primo risultato che non sparisca con l'analyzer
        Optional<String> category = categories(first).stream()
            .filter(c -> {
                try {
                    return !tokens(IndexFields.CATEGORIES, c).isEmpty();
                } catch (IOException e) {
                    return false;
                }
            }).findFirst();
        assumeTrue(category.isPresent(), "il primo risultato non ha categorie utilizzabili");

        // Data: il giorno (UTC) del primo risultato
        LocalDate day = ((Date) val(first, "date")).toInstant().atZone(ZoneOffset.UTC).toLocalDate();

        SearchResponse byCat = run(FREQUENT, category.get(), null, null, false, 1, 10);
        SearchResponse fromDay = run(FREQUENT, "", day.toString(), null, false, 1, 10);
        SearchResponse toDay = run(FREQUENT, "", null, day.toString(), false, 1, 10);
        SearchResponse sameDay = run(FREQUENT, "", day.toString(), day.toString(), false, 1, 10);
        SearchResponse everything = run(FREQUENT, category.get(), day.toString(), day.toString(), false, 1, 10);
        long before = hits(run(FREQUENT, "", null, day.minusDays(1).toString(), false, 1, 10));

        log("Primo risultato: %s", str(first, "title"));
        log("Filtro categoria '%s'     -> %d (senza filtro: %d)", category.get(), hits(byCat), total);
        log("Filtro dal %s             -> %d", day, hits(fromDay));
        log("Filtro fino al %s         -> %d", day, hits(toDay));
        log("Filtro solo il giorno %s  -> %d", day, hits(sameDay));
        log("Categoria + giorno insieme -> %d", hits(everything));
        log("Fino al giorno prima (%s) + dal giorno (%d) = %d (<= %d)", before, hits(fromDay), before + hits(fromDay), total);

        assertAll(
            () -> assertTrue(hits(byCat) <= total && hits(byCat) >= 1),
            () -> assertTrue(hits(fromDay) <= total),
            () -> assertTrue(hits(toDay) <= total),
            () -> assertTrue(hits(sameDay) >= 1 && hits(sameDay) <= Math.min(hits(fromDay), hits(toDay)),
                "l'estremo e' incluso: il primo risultato deve restare"),
            () -> assertTrue(hits(everything) >= 1 && hits(everything) <= Math.min(hits(byCat), hits(sameDay))),
            () -> assertTrue(before + hits(fromDay) <= total, "i due intervalli non si sovrappongono"),
            // I filtri non cambiano lo score: il primo risultato resta primo
            () -> assertEquals(firstUrl, urls(byCat).get(0)),
            () -> assertEquals(firstUrl, urls(sameDay).get(0)),
            () -> assertEquals(firstUrl, urls(everything).get(0))
        );
        summary.put("03 Invarianti: i filtri non aumentano i risultati",
            "tot=" + total + ", categoria=" + hits(byCat) + ", giorno=" + hits(sameDay));
    }

    @Test
    @Order(4)
    @DisplayName("04 Invarianti: operatori booleani")
    void booleanOperators() throws Exception {
        step("Invarianti: +/-, OR e frasi (relazioni insiemistiche tra i risultati)");

        long a = hits(FREQUENT);
        long b = hits(RARE);
        long and = hits("+" + FREQUENT + " +" + RARE);
        long aNotB = hits(FREQUENT + " -" + RARE);
        long bNotA = hits(RARE + " -" + FREQUENT);
        long phrase = hits("\"" + FREQUENT + " " + RARE + "\"");
        long or = hits(FREQUENT + " OR " + RARE);       // OR esplicito
        long implicit = hits(FREQUENT + " " + RARE);    // spazio = operatore di default
        
        log("Operatore implicito rilevato: %s", implicit == or ? "OR" : (implicit == and ? "AND" : "altro"));
        log("A = '%s' = %d, B = '%s' = %d", FREQUENT, a, RARE, b);
        log("A e B (+A +B)        = %d", and);
        log("A oppure B (A B)     = %d   (attesi |A|+|B|-|A e B| = %d)", or, a + b - and);
        log("A senza B (A -B)     = %d   (attesi |A|-|A e B| = %d)", aNotB, a - and);
        log("B senza A (B -A)     = %d   (attesi |B|-|A e B| = %d)", bNotA, b - and);
        log("frase \"A B\"          = %d   (<= A e B)", phrase);

        assertAll(
            () -> assertTrue(and <= Math.min(a, b), "A e B e' contenuto in A e in B"),
            () -> assertEquals(a + b - and, or, "inclusione-esclusione: |A o B| = |A| + |B| - |A e B|"),
            () -> assertEquals(a - and, aNotB, "A = (A senza B) + (A e B)"),
            () -> assertEquals(b - and, bNotA, "B = (B senza A) + (A e B)"),
            () -> assertTrue(phrase <= and, "una frase 'A B' implica che ci siano sia A sia B")
        );
        summary.put("04 Invarianti: operatori booleani",
            "A=" + a + ", B=" + b + ", A e B=" + and + ", A o B=" + or);
    }

    @Test
    @Order(5)
    @DisplayName("05 Invarianti: coerenza della paginazione")
    void paginationConsistency() throws Exception {
        step("Invarianti: le pagine 1..5 da 10 = la pagina 1 da 50");

        List<String> pages = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            pages.addAll(urls(run(FREQUENT, "", null, null, false, p, 10)));
        }
        List<String> oneBig = urls(run(FREQUENT, "", null, null, false, 1, 50));

        log("Risultati da 5 pagine: %d, da una pagina grande: %d, distinti: %d",
            pages.size(), oneBig.size(), new HashSet<>(pages).size());

        assertAll(
            () -> assertEquals(oneBig, pages, "stessi risultati e stesso ordine"),
            () -> assertEquals(pages.size(), new HashSet<>(pages).size(), "nessun duplicato tra le pagine")
        );
        summary.put("05 Invarianti: coerenza della paginazione", pages.size() + " risultati, nessun duplicato");
    }

    // ==================================================================
    // B. QUALITA' DEL RANKING (known-item search)
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("06 Ranking: known-item search sul sistema reale")
    void knownItemReal() throws Exception {
        step("Known-item search: " + targets.size() + " articoli a caso, query = titolo / prime "
            + ABSTRACT_WORDS + " parole dell'abstract");
        System.out.println("  Success@k = % di query in cui l'articolo di partenza e' nei primi k risultati;");
        System.out.println("  MRR = media di 1/posizione (1 = sempre primo, 0 = mai nei primi 10).");

        Metrics title = evaluateReal(true);
        Metrics abstr = evaluateReal(false);
        printMetrics("query = titolo", title);
        printMetrics("query = abstract", abstr);

        // Controllo di coerenza: la nostra "variante baseline" (usata nelle ablazioni) deve dare
        // gli stessi risultati di SearchService/QueryFactory reali.
        Variant baseline = variant("baseline", null, ALL_FIELDS, BASE_BOOSTS, false);
        Metrics baseTitle = evaluate(baseline, true);
        log("Controllo: MRR reale (%.4f) = MRR della variante baseline (%.4f)", title.mrr(), baseTitle.mrr());

        assertAll(
            () -> assertTrue(title.n() > 0),
            () -> assertTrue(title.mrr() > 0.5, "cercando il titolo esatto l'articolo dovrebbe essere quasi sempre in alto"),
            () -> assertEquals(title.mrr(), baseTitle.mrr(), 1e-9, "la baseline replica fedelmente QueryFactory")
        );
        summary.put("06 Ranking: known-item search sul sistema reale",
            String.format("titolo: S@1=%.1f%% MRR=%.3f | abstract: S@10=%.1f%% MRR=%.3f",
                title.s1() * 100, title.mrr(), abstr.s10() * 100, abstr.mrr()));
    }

    // ==================================================================
    // C. ABLAZIONI
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("07 Ablazione: boost dei campi")
    void ablationBoosts() throws Exception {
        step("Ablazione 1 - Boost dei campi (name/abstract/content/categories)");
        header();
        List<Result> res = new ArrayList<>();
        res.add(row(variant("baseline 3/2/1/1", null, ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("tutti i campi boost 1", null, ALL_FIELDS, boosts(1, 1, 1, 1), false)));
        res.add(row(variant("titolo molto alto 10/2/1/1", null, ALL_FIELDS, boosts(10, 2, 1, 1), false)));
        res.add(row(variant("titolo basso 1/2/3/1", null, ALL_FIELDS, boosts(1, 2, 3, 1), false)));
        summarizeBest("07 Ablazione: boost dei campi", res);
    }

    @Test
    @Order(8)
    @DisplayName("08 Ablazione: campi cercati")
    void ablationFields() throws Exception {
        step("Ablazione 2 - Quali campi vengono cercati");
        header();
        List<Result> res = new ArrayList<>();
        res.add(row(variant("tutti e 4 (baseline)", null, ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("senza categories", null,
            new String[] {IndexFields.NAME, IndexFields.ABSTRACT, IndexFields.CONTENT},
            restrict(IndexFields.NAME, IndexFields.ABSTRACT, IndexFields.CONTENT), false)));
        res.add(row(variant("name + content", null,
            new String[] {IndexFields.NAME, IndexFields.CONTENT},
            restrict(IndexFields.NAME, IndexFields.CONTENT), false)));
        res.add(row(variant("solo content", null,
            new String[] {IndexFields.CONTENT}, restrict(IndexFields.CONTENT), false)));
        res.add(row(variant("solo name", null,
            new String[] {IndexFields.NAME}, restrict(IndexFields.NAME), false)));
        summarizeBest("08 Ablazione: campi cercati", res);
    }

    @Test
    @Order(9)
    @DisplayName("09 Ablazione: operatore di default")
    void ablationOperator() throws Exception {
        step("Ablazione 3 - Operatore predefinito tra le parole: OR (baseline) contro AND");
        header();
        List<Result> res = new ArrayList<>();
        res.add(row(variant("OR (baseline)", null, ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("AND", null, ALL_FIELDS, BASE_BOOSTS, true)));
        summarizeBest("09 Ablazione: operatore di default", res);
    }

    @Test
    @Order(10)
    @DisplayName("10 Ablazione: similarity")
    void ablationSimilarity() throws Exception {
        step("Ablazione 4 - Modello di ranking (similarity)");
        header();
        List<Result> res = new ArrayList<>();
        res.add(row(variant("BM25 1.2/0.75 (baseline)", null, ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("ClassicSimilarity (TF-IDF)", new ClassicSimilarity(), ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("LM Jelinek-Mercer 0.1", new LMJelinekMercerSimilarity(0.1f), ALL_FIELDS, BASE_BOOSTS, false)));
        res.add(row(variant("LM Dirichlet", new LMDirichletSimilarity(), ALL_FIELDS, BASE_BOOSTS, false)));
        summarizeBest("10 Ablazione: similarity", res);
    }

    @Test
    @Order(11)
    @DisplayName("11 Ablazione: parametri BM25")
    void ablationBm25() throws Exception {
        step("Ablazione 5 - Parametri BM25: k1 (saturazione tf) e b (normalizzazione lunghezza)");
        header();
        List<Result> res = new ArrayList<>();
        for (float k1 : new float[] {0.5f, 1.2f, 2.0f}) {
            for (float b : new float[] {0.0f, 0.75f, 1.0f}) {
                String name = String.format("BM25 k1=%.1f b=%.2f%s", k1, b, (k1 == 1.2f && b == 0.75f) ? " (baseline)" : "");
                res.add(row(variant(name, new BM25Similarity(k1, b), ALL_FIELDS, BASE_BOOSTS, false)));
            }
        }
        summarizeBest("11 Ablazione: parametri BM25", res);
    }

    @Test
    @Order(12)
    @DisplayName("12 Ablazione: analyzer (reindicizzazione)")
    void ablationAnalyzer() throws Exception {
        assumeTrue(RUN_ANALYZER_ABLATION, "ablazione analyzer disattivata (RUN_ANALYZER_ABLATION)");
        step("Ablazione 6 - Analyzer: richiede di reindicizzare tutti gli articoli in memoria");
        System.out.println("  (gli articoli vengono letti dall'indice reale e reindicizzati con un analyzer diverso)");

        Map<String, Analyzer> variants = new LinkedHashMap<>();
        variants.put("baseline reindicizzata (AnalyzerFactory)", AnalyzerFactory.createAnalyzer());
        variants.put("StandardAnalyzer (niente stemming/stopword)", new StandardAnalyzer());
        variants.put("ItalianAnalyzer anche sulle categorie",
            new ItalianAnalyzer());
        variants.put("categorie senza stopword personalizzate",
            new PerFieldAnalyzerWrapper(new ItalianAnalyzer(),
                Map.of(IndexFields.CATEGORIES, categoryAnalyzer(false))));

        header();
        List<Result> res = new ArrayList<>();
        Map<String, Long> categoryNoise = new LinkedHashMap<>();
        for (Map.Entry<String, Analyzer> e : variants.entrySet()) {
            long t0 = System.nanoTime();
            IndexSearcher searcher = reindex(e.getValue());
            log("(reindicizzazione '%s': %d ms)", e.getKey(), (System.nanoTime() - t0) / 1_000_000);
            Variant v = new Variant(e.getKey(), searcher, e.getValue(), ALL_FIELDS, BASE_BOOSTS, false);
            res.add(row(v));
            // Sonda: quanti articoli hanno "pubblicati" nel campo categories?
            categoryNoise.put(e.getKey(), (long) searcher.count(v.query("categories:pubblicati")));
        }

        System.out.println("\n  Effetto delle stopword sulle categorie: articoli trovati da  categories:pubblicati");
        categoryNoise.forEach((k, v) -> System.out.printf("    %-46s -> %d%n", k, v));

        // Controllo di coerenza: reindicizzare con lo stesso analyzer non deve cambiare il ranking
        assertEquals(res.get(0).title().mrr(), evaluate(variant("baseline", null, ALL_FIELDS, BASE_BOOSTS, false), true).mrr(),
            0.02, "la reindicizzazione con l'analyzer reale deve dare risultati equivalenti all'indice reale");
        summarizeBest("12 Ablazione: analyzer (reindicizzazione)", res);
    }

    // ==================================================================
    // METRICHE: known-item search
    // ==================================================================

    /** Known-item search usando il SearchService REALE. */
    private Metrics evaluateReal(boolean useTitle) throws Exception {
        Acc acc = new Acc();
        for (Target t : targets) {
            String text = queryText(t, useTitle);
            if (text.isBlank()) {
                continue;
            }
            long t0 = System.nanoTime();
            SearchResponse r;
            try {
                r = service.search(QueryParser.escape(text), "", null, null, false, 1, 10);
            } catch (ParseException e) {
                continue;   // titolo con sintassi Lucene strana (es. "AND" da solo): lo saltiamo
            }
            long ns = System.nanoTime() - t0;
            acc.add(rankOf(urls(r), t.url()), ns);
        }
        return acc.done();
    }

    /** Known-item search su una variante (stessa idea, ma con parametri di ricerca modificabili). */
    private Metrics evaluate(Variant v, boolean useTitle) throws IOException {
        Acc acc = new Acc();
        StoredFields stored = v.searcher().storedFields();
        for (Target t : targets) {
            String text = queryText(t, useTitle);
            if (text.isBlank()) {
                continue;
            }
            Query q;
            try {
                q = v.query(QueryParser.escape(text));
            } catch (ParseException e) {
                continue;
            }
            long t0 = System.nanoTime();
            TopDocs top = v.searcher().search(q, 10);
            long ns = System.nanoTime() - t0;

            List<String> urls = new ArrayList<>();
            for (int i = 0; i < top.scoreDocs.length; i++) {
                urls.add(stored.document(top.scoreDocs[i].doc, Set.of(IndexFields.URL)).get(IndexFields.URL));
            }
            acc.add(rankOf(urls, t.url()), ns);
        }
        return acc.done();
    }

    private static String queryText(Target t, boolean useTitle) {
        if (useTitle) {
            return t.title();
        }
        String[] words = t.abstr().trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(words, Math.min(ABSTRACT_WORDS, words.length)));
    }

    /** Posizione (1..10) dell'articolo cercato nella lista, 0 se non c'e'. */
    private static int rankOf(List<String> urls, String target) {
        int idx = urls.indexOf(target);
        return idx < 0 ? 0 : idx + 1;
    }

    /** Accumulatore delle metriche. */
    private static final class Acc {
        int n;
        int h1;
        int h5;
        int h10;
        double rr;
        long nanos;

        void add(int rank, long ns) {
            n++;
            nanos += ns;
            if (rank > 0) {                 // trovato nei primi 10
                rr += 1.0 / rank;
                h10++;
                if (rank <= 5) {
                    h5++;
                }
                if (rank == 1) {
                    h1++;
                }
            }
        }

        Metrics done() {
            double d = Math.max(n, 1);
            return new Metrics(n, h1 / d, h5 / d, h10 / d, rr / d, nanos / 1e6 / d);
        }
    }

    // ==================================================================
    // SUPPORTO: varianti, reindicizzazione, stampa
    // ==================================================================

    private Variant variant(String name, Similarity sim, String[] fields, Map<String, Float> boosts, boolean and) {
        IndexSearcher s = new IndexSearcher(base.getIndexReader());
        if (sim != null) {
            s.setSimilarity(sim);
        }
        return new Variant(name, s, analyzer, fields, boosts, and);
    }

    private static Map<String, Float> boosts(float name, float abstr, float content, float categories) {
        return Map.of(IndexFields.NAME, name, IndexFields.ABSTRACT, abstr,
            IndexFields.CONTENT, content, IndexFields.CATEGORIES, categories);
    }

    /** I boost della baseline, ma solo per i campi indicati. */
    private static Map<String, Float> restrict(String... fields) {
        Map<String, Float> m = new HashMap<>();
        for (String f : fields) {
            m.put(f, BASE_BOOSTS.get(f));
        }
        return m;
    }

    /** Analyzer delle categorie come in AnalyzerFactory; con extraStopwords=false senza "pubblicati"/"articoli". */
    private static Analyzer categoryAnalyzer(boolean extraStopwords) {
        CharArraySet stop = new CharArraySet(ItalianAnalyzer.getDefaultStopSet(), true);
        if (extraStopwords) {
            stop.add("pubblicati");
            stop.add("articoli");
        }
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                TokenStream ts = new LowerCaseFilter(tokenizer);
                ts = new StopFilter(ts, stop);
                return new TokenStreamComponents(tokenizer, ts);
            }
        };
    }

    /** Rilegge tutti gli articoli dall'indice reale e li reindicizza in memoria con l'analyzer dato. */
    private IndexSearcher reindex(Analyzer a) throws IOException {
        Directory mem = new ByteBuffersDirectory();
        IndexReader reader = base.getIndexReader();
        Bits live = MultiBits.getLiveDocs(reader);
        StoredFields stored = reader.storedFields();
        try (IndexWriter w = new IndexWriter(mem, new IndexWriterConfig(a))) {
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) {
                    continue;
                }
                w.addDocument(copy(stored.document(i)));
            }
        }
        DirectoryReader newReader = DirectoryReader.open(mem);
        toClose.add(newReader);
        toClose.add(mem);
        return new IndexSearcher(newReader);
    }

    /** Ricostruisce un documento dai campi memorizzati (stessi tipi di campo dell'indicizzatore ipotizzato). */
    private static Document copy(Document src) {
        Document d = new Document();
        String id = src.get(IndexFields.ID);
        if (id != null) {
            d.add(new StringField(IndexFields.ID, id, Field.Store.YES));
        }
        String url = src.get(IndexFields.URL);
        if (url != null) {
            d.add(new StoredField(IndexFields.URL, url));
        }
        for (String f : ALL_FIELDS) {
            String v = src.get(f);
            if (v != null) {
                d.add(new TextField(f, v, Field.Store.YES));
            }
        }
        IndexableField date = src.getField(IndexFields.DATE_MODIFIED);
        if (date != null && date.numericValue() != null) {
            long millis = date.numericValue().longValue();
            d.add(new LongPoint(IndexFields.DATE_MODIFIED, millis));
            d.add(new StoredField(IndexFields.DATE_MODIFIED, millis));
            d.add(new NumericDocValuesField(IndexFields.DATE_MODIFIED, millis));
        }
        return d;
    }

    /** Token prodotti dall'analyzer reale per un campo. */
    private List<String> tokens(String field, String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        }
        return out;
    }

    // --- SearchService reale -------------------------------------------------------

    private SearchResponse run(String q, String category, String from, String to,
                               boolean byDate, int page, int pageSize) throws Exception {
        return service.search(q, category,
            from == null ? null : LocalDate.parse(from),
            to == null ? null : LocalDate.parse(to),
            byDate, page, pageSize);
    }

    private long hits(String q) throws Exception {
        return hits(run(q, "", null, null, false, 1, 10));
    }

    // --- Accesso a SearchResponse / SearchResult (record o getter: prova entrambi) --

    private static Object val(Object o, String prop) {
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (String name : new String[] {prop, "get" + cap, "is" + cap}) {
            try {
                Method m = o.getClass().getMethod(name);
                return m.invoke(o);
            } catch (NoSuchMethodException ignored) {
                // provo il nome successivo
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Nessun accessor per '" + prop + "' in " + o.getClass().getSimpleName());
    }

    private static long hits(SearchResponse r) {
        return ((Number) val(r, "totalHits")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<SearchResult> results(SearchResponse r) {
        return (List<SearchResult>) val(r, "results");
    }

    private static String str(SearchResult r, String prop) {
        return String.valueOf(val(r, prop));
    }

    @SuppressWarnings("unchecked")
    private static List<String> categories(SearchResult r) {
        return (List<String>) val(r, "categories");
    }

    private static List<String> urls(SearchResponse r) {
        return results(r).stream().map(x -> str(x, "url")).toList();
    }

    // --- Stampa ----------------------------------------------------------------------

    private static void log(String format, Object... args) {
        System.out.println("  " + String.format(format, args));
    }

    private static void step(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    private static void printMetrics(String label, Metrics m) {
        log("%-18s n=%d  Success@1=%5.1f%%  @5=%5.1f%%  @10=%5.1f%%  MRR=%.4f  (%.3f ms/query)",
            label, m.n(), m.s1() * 100, m.s5() * 100, m.s10() * 100, m.mrr(), m.msPerQuery());
    }

    private static void header() {
        System.out.println("  T = query dal titolo, A = query dalle prime " + ABSTRACT_WORDS
            + " parole dell'abstract. Success@k in %, MRR in [0,1].");
        System.out.printf("  %-44s | %-31s | %-31s | %-8s %-8s | %s%n",
            "Variante", "T:  S@1    S@5   S@10   MRR", "A:  S@1    S@5   S@10   MRR",
            FREQUENT, RARE, "ms/q");
    }

    /** Valuta una variante, stampa una riga di tabella e restituisce i risultati. */
    private Result row(Variant v) throws Exception {
        Metrics t = evaluate(v, true);
        Metrics a = evaluate(v, false);
        long hf = v.searcher().count(v.query(FREQUENT));
        long hr = v.searcher().count(v.query(RARE));
        System.out.printf("  %-44s |   %5.1f %6.1f %6.1f  %.3f |   %5.1f %6.1f %6.1f  %.3f | %-8d %-8d | %.2f%n",
            v.name(), t.s1() * 100, t.s5() * 100, t.s10() * 100, t.mrr(),
            a.s1() * 100, a.s5() * 100, a.s10() * 100, a.mrr(), hf, hr,
            (t.msPerQuery() + a.msPerQuery()) / 2);
        assertTrue(t.n() > 0, "nessuna query valutata");
        return new Result(v.name(), t, a);
    }

    /** Registra nel riepilogo la variante con MRR (titolo + abstract) piu' alto. */
    private void summarizeBest(String test, List<Result> res) {
        Result best = res.get(0);
        for (Result r : res) {
            if (r.title().mrr() + r.abstr().mrr() > best.title().mrr() + best.abstr().mrr()) {
                best = r;
            }
        }
        summary.put(test, String.format("migliore: %s (MRR titolo %.3f, abstract %.3f)",
            best.name(), best.title().mrr(), best.abstr().mrr()));
    }

    private void printSummary() {
        if (summary.isEmpty()) {
            return;
        }
        System.out.println("\n================ RIEPILOGO ================");
        for (Map.Entry<String, String> e : summary.entrySet()) {
            System.out.printf("%-50s %7d ms  %s%n", e.getKey(), durationsMs.getOrDefault(e.getKey(), -1L), e.getValue());
        }
        System.out.println("===========================================\n");
    }
}