package it.wikinews;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
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
import it.wikinews.lucene.search.SearchResponse;
import it.wikinews.lucene.search.SearchResult;
import it.wikinews.lucene.search.SearchService;

/**
 * Test della logica di ricerca di Wikinews Search.
 *
 * <p>
 * <b>Idea di fondo:</b> i test NON usano l'indice reale. Ogni test lavora su un
 * piccolo
 * indice Lucene <i>in memoria</i> (7 articoli inventati, con contenuto noto),
 * costruito con
 * gli stessi componenti dell'applicazione: {@link AnalyzerFactory},
 * {@link IndexFields},
 * {@link ArticleSearcher} e {@link SearchService} (che a sua volta usa
 * QueryFactory).
 * In questo modo i risultati attesi sono prevedibili e i test sono veloci e
 * ripetibili.
 *
 * <p>
 * Ogni test stampa su console i risultati e le statistiche; al termine viene
 * stampato
 * un riepilogo finale con metriche e durata di ogni test.
 *
 * <p>
 * Assunzione: i campi dell'indice di test sono creati come farebbe
 * l'indicizzatore reale
 * (testo analizzato e memorizzato per name/abstract/content/categories; data
 * come
 * LongPoint + StoredField + NumericDocValuesField). Se l'indicizzatore reale li
 * crea in
 * modo diverso, adatta il metodo {@link Fixture#add(Article)}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Wikinews Search - test della logica di sistema")
class WikinewsApplicationTests {

    // ------------------------------------------------------------------
    // DATI DI TEST
    // ------------------------------------------------------------------

    /** Un articolo di prova (equivale a un documento dell'indice). */
    private record Article(String id, String name, String abstr, String content,
            String categories, String isoDate) {
        long millis() {
            return Instant.parse(isoDate).toEpochMilli();
        }
    }

    /**
     * Gli articoli dell'indice di test. Sono costruiti in modo che ogni test abbia
     * un
     * risultato atteso preciso (vedi i commenti accanto a ciascun articolo).
     */
    private static final List<Article> SAMPLE = List.of(
            // d1: "terremoto" nel titolo E nel contenuto -> deve vincere nel test dei boost
            new Article("d1", "Terremoto in Abruzzo",
                    "Una scossa di magnitudo 5 è stata avvertita a L'Aquila e in tutta la regione.",
                    "Una forte scossa di terremoto ha colpito questa mattina l'Abruzzo. "
                            + "La protezione civile ha avviato i controlli sugli edifici e non si registrano vittime.",
                    "Cronaca,Terremoti,Articoli pubblicati", "2024-03-10T15:30:00Z"),

            // d2: "elezioni europee" (frase esatta) in titolo e contenuto
            new Article("d2", "Elezioni europee 2024: affluenza in calo",
                    "Si è votato in tutta Italia per rinnovare il Parlamento europeo.",
                    "Si sono svolte le elezioni europee: l'affluenza è stata in calo rispetto alla tornata precedente. "
                            + "Lo spoglio si è concluso nella notte.",
                    "Politica,Elezioni", "2024-06-09T20:00:00Z"),

            // d3: "elezioni" ma NON "europee"
            new Article("d3", "Elezioni comunali a Roma: ballottaggio",
                    "Il ballottaggio decide il nuovo sindaco della capitale.",
                    "Gli elettori romani sono tornati alle urne per le elezioni comunali. Il sindaco uscente è in vantaggio.",
                    "Politica,Roma", "2023-10-05T09:00:00Z"),

            // d4: "calcio" senza la parola "serie" (serve per +calcio -serie)
            new Article("d4", "Calcio: la Juventus vince il derby",
                    "Decide un gol nel finale.",
                    "La Juventus ha battuto i rivali cittadini con un gol all'ultimo minuto. Grande festa allo stadio.",
                    "Sport,Calcio", "2024-02-18T21:00:00Z"),

            // d5: "calcio" e "serie" (viene escluso da +calcio -serie)
            new Article("d5", "Calcio: il campionato di serie B riprende",
                    "Dopo la sosta tornano le partite della cadetteria.",
                    "Riprende il campionato di calcio di serie B con otto partite in programma.",
                    "Sport,Calcio", "2022-09-01T12:00:00Z"),

            // d6: abstract con HTML e "&" -> serve per testare l'escape dello snippet di
            // fallback
            new Article("d6", "Lanciato un nuovo telescopio spaziale",
                    "Il telescopio <b>spaziale</b> osserverà le prime galassie & i buchi neri.",
                    "Il lancio è avvenuto con successo. Il telescopio inizierà le osservazioni entro sei mesi.",
                    "Scienza,Spazio", "2024-12-01T08:00:00Z"),

            // d7: "terremoto" SOLO nel contenuto (secondo nel test dei boost)
            new Article("d7", "Maltempo al nord, allerta rossa",
                    "Piogge intense e vento forte su tutta la pianura padana.",
                    "Il maltempo non è un terremoto, ma ha causato allagamenti e danni in molte località.",
                    "Cronaca,Meteo", "2024-03-11T07:00:00Z"));

    // ------------------------------------------------------------------
    // INFRASTRUTTURA: indice in memoria + servizio
    // ------------------------------------------------------------------

    /**
     * Mette insieme i pezzi reali dell'applicazione su un indice in memoria:
     * Directory -> IndexWriter -> SearcherManager -> ArticleSearcher ->
     * SearchService.
     */
    private static final class Fixture implements AutoCloseable {
        final Directory dir = new ByteBuffersDirectory();
        final IndexWriter writer;
        final SearcherManager manager;
        final SearchService service;

        Fixture(List<Article> articles) throws IOException {
            // Lo stesso analyzer usato in ricerca: indicizzazione e query devono coincidere
            writer = new IndexWriter(dir, new IndexWriterConfig(AnalyzerFactory.createAnalyzer()));
            for (Article a : articles) {
                add(a);
            }
            writer.commit();
            // SearcherManager "near real time" sul writer: vede anche i documenti aggiunti
            // dopo
            manager = new SearcherManager(writer, null);
            service = new SearchService(new ArticleSearcher(manager));
        }

        /**
         * Aggiunge un articolo all'indice (senza commit: lo vede il refresh del
         * SearcherManager).
         */
        void add(Article a) throws IOException {
            Document d = new Document();
            d.add(new StringField(IndexFields.ID, a.id(), Field.Store.YES));
            d.add(new StoredField(IndexFields.URL, "https://it.wikinews.org/wiki/" + a.id()));
            d.add(new TextField(IndexFields.NAME, a.name(), Field.Store.YES));
            d.add(new TextField(IndexFields.ABSTRACT, a.abstr(), Field.Store.YES));
            d.add(new TextField(IndexFields.CONTENT, a.content(), Field.Store.YES));
            // Categorie: stringa separata da virgole, come si aspetta splitCategories()
            d.add(new TextField(IndexFields.CATEGORIES, a.categories(), Field.Store.YES));
            // Data: tre rappresentazioni, ognuna con un compito diverso
            d.add(new LongPoint(IndexFields.DATE_MODIFIED, a.millis())); // filtro per intervallo
            d.add(new StoredField(IndexFields.DATE_MODIFIED, a.millis())); // lettura nei risultati
            d.add(new NumericDocValuesField(IndexFields.DATE_MODIFIED, a.millis())); // ordinamento
            writer.addDocument(d);
        }

        @Override
        public void close() throws IOException {
            manager.close();
            writer.close();
            dir.close();
        }
    }

    private Fixture fx; // indice condiviso dai test (sola lettura)
    private Analyzer analyzer; // analyzer dell'applicazione

    // Raccolta di metriche per il riepilogo finale
    private final Map<String, String> summary = new LinkedHashMap<>();
    private final Map<String, Long> durationsMs = new LinkedHashMap<>();
    private long testStartNanos;

    @BeforeAll
    void setUp() throws IOException {
        fx = new Fixture(SAMPLE);
        analyzer = AnalyzerFactory.createAnalyzer();
        System.out.println("\n================ WIKINEWS SEARCH - TEST DI SISTEMA ================");
    }

    @AfterAll
    void tearDown() throws IOException {
        analyzer.close();
        fx.close();
        printSummary();
    }

    @BeforeEach
    void startTimer() {
        testStartNanos = System.nanoTime();
    }

    @AfterEach
    void stopTimer(TestInfo info) {
        durationsMs.put(info.getDisplayName(), (System.nanoTime() - testStartNanos) / 1_000_000);
    }

    // ------------------------------------------------------------------
    // 1. INDICE
    // ------------------------------------------------------------------
    /***
     * <b>01 - Indice.</b> Verifica che l'indice in memoria contenga tutti i 7
     * articoli di prova
     * e stampa, per ogni campo testuale, quanti documenti lo contengono e quanti
     * token totali
     * sono stati indicizzati (utile per accorgersi di campi vuoti o non
     * analizzati).
     * <p>
     * TODO controlla l'indice costruito da {@link Fixture}, non l'indicizzatore
     * reale.
     * Vedi anche il test 14 per i controlli sui singoli campi.
     */
    @Test
    @Order(1)
    @DisplayName("01 Indice: documenti e statistiche")
    void indexStatistics() throws IOException {
        step("Indice in memoria: documenti e statistiche");
        IndexSearcher searcher = fx.manager.acquire();
        try {
            IndexReader reader = searcher.getIndexReader();
            log("Documenti indicizzati: %d", reader.numDocs());
            for (String field : new String[] { IndexFields.NAME, IndexFields.ABSTRACT,
                    IndexFields.CONTENT, IndexFields.CATEGORIES }) {
                log("  campo %-11s -> documenti con il campo: %d, token totali indicizzati: %d",
                        field, reader.getDocCount(field), reader.getSumTotalTermFreq(field));
            }
            assertEquals(SAMPLE.size(), reader.numDocs());
            summary.put("01 Indice: documenti e statistiche", reader.numDocs() + " documenti");
        } finally {
            fx.manager.release(searcher);
        }
    }

    // ------------------------------------------------------------------
    // 2. ANALYZER
    // ------------------------------------------------------------------
    /***
     * <b>02 - Analyzer.</b> Verifica il comportamento dell'analyzer su campi
     * diversi:
     * <ul>
     * <li>testi (name/abstract/content): stemming italiano, per cui "elezione" e
     * "elezioni"
     * producono lo stesso token, e rimozione delle stopword ("il");</li>
     * <li>categorie: nessuno stemming e stopword personalizzate ("articoli",
     * "pubblicati").</li>
     * </ul>
     * Dimostra che indicizzazione e query usano la stessa analisi, condizione
     * necessaria
     * perché i termini cercati coincidano con quelli indicizzati.
     *
     * 
     * @throws IOException
     */
    @Test
    @Order(2)
    @DisplayName("02 Analyzer: stemming, stopword, campi diversi")
    void analyzerBehaviour() throws IOException {
        step("Analyzer: ItalianAnalyzer sui testi, analyzer dedicato sulle categorie");

        List<String> contentPlural = tokens(IndexFields.CONTENT, "elezioni");
        List<String> contentSingular = tokens(IndexFields.CONTENT, "elezione");
        List<String> contentStop = tokens(IndexFields.CONTENT, "il terremoto");
        List<String> catElezioni = tokens(IndexFields.CATEGORIES, "Elezioni");
        List<String> catStop = tokens(IndexFields.CATEGORIES, "Articoli pubblicati Sport");

        log("content    'elezioni'                  -> %-10s", contentPlural);
        log("content    'elezione'                  -> %-10s", contentSingular);
        log("content    'il terremoto'              -> %-10s   (stopword 'il' rimossa)", contentStop);
        log("categories 'Elezioni'                  -> %-10s   (nessuno stemming)", catElezioni);
        log("categories 'Articoli pubblicati Sport' -> %-10s   (stopword personalizzate rimosse)", catStop);

        assertAll(
                // Lo stemming italiano porta singolare e plurale alla stessa radice
                () -> assertEquals(contentPlural, contentSingular, "singolare e plurale devono avere la stessa radice"),
                // 'il' e' una stopword italiana
                () -> assertFalse(contentStop.contains("il"), "la stopword 'il' deve essere rimossa"),
                // Sulle categorie non si applica lo stemming...
                () -> assertEquals(List.of("elezioni"), catElezioni),
                // ...quindi il token dei testi e quello delle categorie sono diversi
                () -> assertNotEquals(contentPlural, catElezioni),
                // 'articoli' e 'pubblicati' sono stopword aggiunte da AnalyzerFactory
                () -> assertEquals(List.of("sport"), catStop));
        summary.put("02 Analyzer: stemming, stopword, campi diversi", "5 verifiche sui token");
    }

    // ------------------------------------------------------------------
    // 3. QUERY DI TESTO
    // ------------------------------------------------------------------
    /***
     * <b>03 - Ricerca testuale e boost.</b> Cerca "terremoto", presente nel titolo
     * e nel
     * contenuto di d1 e solo nel contenuto di d7. Verifica che i risultati siano 2,
     * che d1
     * sia primo e che il suo score sia strettamente maggiore: il match nel titolo
     * (boost x3)
     * deve pesare più del match nel solo contenuto (x1).
     * 
     * @throws Exception
     */
    @Test
    @Order(3)
    @DisplayName("03 Testo: ricerca semplice e boost dei campi")
    void textSearchAndBoost() throws Exception {
        step("Ricerca testuale: 'terremoto' (boost titolo x3, abstract x2, contenuto x1)");
        SearchResponse r = search("terremoto");
        printResponse("terremoto", r);

        List<String> titles = titles(r);
        assertAll(
                () -> assertEquals(2, hits(r), "'terremoto' compare in 2 articoli (d1 e d7)"),
                // d1 ha la parola nel titolo (boost x3), d7 solo nel contenuto
                () -> assertEquals("Terremoto in Abruzzo", titles.get(0), "il match nel titolo deve pesare di piu'"),
                () -> assertEquals("Maltempo al nord, allerta rossa", titles.get(1)),
                () -> assertTrue(score(results(r).get(0)) > score(results(r).get(1)),
                        "lo score del primo deve essere maggiore del secondo"));
        summary.put("03 Testo: ricerca semplice e boost dei campi",
                "2 hit, score " + fmt(score(results(r).get(0))) + " > " + fmt(score(results(r).get(1))));
    }

    /**
     * <b>04 - Stemming in query.</b> Cerca "elezione" (singolare) e verifica che
     * vengano
     * trovati d2 e d3, che contengono "elezioni". Dimostra che la query viene
     * analizzata
     * con lo stesso analyzer dell'indice.
     *
     * @throws Exception se la ricerca fallisce
     */

    @Test
    @Order(4)
    @DisplayName("04 Testo: stemming in query (elezione -> elezioni)")
    void stemmingInQuery() throws Exception {
        step("Stemming: cercando 'elezione' (singolare) si trovano gli articoli con 'elezioni'");
        SearchResponse r = search("elezione");
        printResponse("elezione", r);

        assertEquals(2, hits(r), "d2 e d3 contengono 'elezioni'");
        summary.put("04 Testo: stemming in query (elezione -> elezioni)", hits(r) + " hit");
    }

    /**
     * <b>05 - Sintassi Lucene.</b> Verifica le funzioni del QueryParser esposte
     * all'utente:
     * <ul>
     * <li>frase esatta {@code "elezioni europee"}: solo d2 (d3 non contiene
     * "europee");</li>
     * <li>{@code +calcio -serie}: solo d4, perché d5 contiene "serie";</li>
     * <li>{@code categories:sport}: ricerca su un campo esplicito (d4, d5);</li>
     * <li>{@code terremoto calcio}: operatore di default OR, quindi 4
     * risultati.</li>
     * </ul>
     *
     * @throws Exception se una query valida viene rifiutata
     */
    @Test
    @Order(5)
    @DisplayName("05 Sintassi Lucene: frase, +/-, campo:valore, OR")
    void luceneSyntax() throws Exception {
        step("Sintassi Lucene accettata dal QueryParser");

        SearchResponse phrase = search("\"elezioni europee\"");
        printResponse("\"elezioni europee\"  (frase esatta)", phrase);

        SearchResponse plusMinus = search("+calcio -serie");
        printResponse("+calcio -serie  (obbligatorio / escluso)", plusMinus);

        SearchResponse field = search("categories:sport");
        printResponse("categories:sport  (campo esplicito)", field);

        SearchResponse or = search("terremoto calcio");
        printResponse("terremoto calcio  (operatore di default: OR)", or);

        assertAll(
                // La frase esatta trova solo d2 (d3 ha 'elezioni' ma non 'europee')
                () -> assertEquals(List.of("Elezioni europee 2024: affluenza in calo"), titles(phrase)),
                // d5 contiene 'serie' e viene escluso: resta solo d4
                () -> assertEquals(List.of("Calcio: la Juventus vince il derby"), titles(plusMinus)),
                // categories:sport -> d4 e d5
                () -> assertEquals(2, hits(field)),
                // OR: d1 + d7 (terremoto) + d4 + d5 (calcio)
                () -> assertEquals(4, hits(or)));
        summary.put("05 Sintassi Lucene: frase, +/-, campo:valore, OR",
                "frase=" + hits(phrase) + ", +/-=" + hits(plusMinus) + ", campo=" + hits(field) + ", OR=" + hits(or));
    }

    // ------------------------------------------------------------------
    // 4. FILTRI
    // ------------------------------------------------------------------

    /**
     * <b>06 - Filtro categoria.</b> Verifica il filtro come frase esatta, da solo e
     * combinato
     * con il testo. Controlla anche che una categoria con caratteri speciali
     * ({@code sport) OR (}) venga escapata: nessuna eccezione e nessun risultato,
     * quindi
     * l'input dell'utente non può alterare la struttura della query.
     *
     * @throws Exception se la ricerca fallisce
     */
    @Test
    @Order(6)
    @DisplayName("06 Filtro categoria")
    void categoryFilter() throws Exception {
        step("Filtro categoria (frase esatta, non influisce sullo score)");

        SearchResponse onlyCat = search("", "Calcio", null, null, false, 1, 10);
        printResponse("[solo categoria = Calcio]", onlyCat);

        SearchResponse textAndCat = search("elezioni", "Politica", null, null, false, 1, 10);
        printResponse("elezioni + categoria = Politica", textAndCat);

        SearchResponse narrow = search("elezioni", "Roma", null, null, false, 1, 10);
        printResponse("elezioni + categoria = Roma", narrow);

        // Caratteri speciali nella categoria: vengono "escapati", non devono dare
        // errore di sintassi
        SearchResponse weird = search("", "sport) OR (", null, null, false, 1, 10);
        printResponse("[categoria con caratteri speciali]  sport) OR (", weird);

        assertAll(
                () -> assertEquals(2, hits(onlyCat), "d4 e d5 hanno la categoria Calcio"),
                () -> assertEquals(2, hits(textAndCat), "d2 e d3 sono entrambi di Politica"),
                () -> assertEquals(List.of("Elezioni comunali a Roma: ballottaggio"), titles(narrow)),
                () -> assertEquals(0, hits(weird), "nessun errore e nessun risultato"));
        summary.put("06 Filtro categoria",
                "Calcio=" + hits(onlyCat) + ", Politica=" + hits(textAndCat) + ", Roma=" + hits(narrow));
    }

    /**
     * <b>07 - Filtro per data.</b> Verifica il filtro su {@code date_modified} con
     * data
     * "dal", "fino al", intervallo di un solo giorno (il giorno finale è incluso
     * per intero,
     * anche se l'articolo è alle 15:30), intervalli nel futuro e combinazione con
     * testo e
     * categoria. Le date sono interpretate in UTC.
     *
     * @throws Exception se la ricerca fallisce
     */
    @Test
    @Order(7)
    @DisplayName("07 Filtro date (estremi inclusi, UTC)")
    void dateFilter() throws Exception {
        step("Filtro intervallo di date su date_modified (estremi inclusi)");

        SearchResponse fromOnly = search("", "", "2024-01-01", null, false, 1, 10);
        SearchResponse toOnly = search("", "", null, "2023-12-31", false, 1, 10);
        // d1 e' alle 15:30 del 10/03/2024: con from = to = 10/03 deve comunque
        // comparire
        SearchResponse sameDay = search("", "", "2024-03-10", "2024-03-10", false, 1, 10);
        SearchResponse twoDays = search("", "", "2024-03-10", "2024-03-11", false, 1, 10);
        SearchResponse future = search("", "", "2030-01-01", null, false, 1, 10);
        SearchResponse combined = search("elezioni", "Politica", "2024-01-01", null, false, 1, 10);

        log("dal 2024-01-01                       -> %d risultati", hits(fromOnly));
        log("fino al 2023-12-31                   -> %d risultati", hits(toOnly));
        log("solo 2024-03-10 (from = to)          -> %d risultato: %s", hits(sameDay), titles(sameDay));
        log("dal 2024-03-10 al 2024-03-11         -> %d risultati", hits(twoDays));
        log("dal 2030-01-01                       -> %d risultati", hits(future));
        log("elezioni + Politica + dal 2024-01-01 -> %d risultato: %s", hits(combined), titles(combined));

        assertAll(
                () -> assertEquals(5, hits(fromOnly), "d1, d2, d4, d6, d7"),
                () -> assertEquals(2, hits(toOnly), "d3 e d5"),
                () -> assertEquals(1, hits(sameDay), "il giorno finale e' incluso per intero"),
                () -> assertEquals(2, hits(twoDays), "d1 e d7"),
                () -> assertEquals(0, hits(future)),
                () -> assertEquals(List.of("Elezioni europee 2024: affluenza in calo"), titles(combined)));
        summary.put("07 Filtro date (estremi inclusi, UTC)",
                "da=" + hits(fromOnly) + ", fino a=" + hits(toOnly) + ", stesso giorno=" + hits(sameDay));
    }

    // ------------------------------------------------------------------
    // 5. ORDINAMENTO E PAGINAZIONE
    // ------------------------------------------------------------------

    /**
     * <b>08 - Ordinamento.</b> Per rilevanza verifica che gli score siano non
     * crescenti.
     * Per data verifica che le date siano non crescenti, che il primo sia il più
     * recente
     * (d6) e l'ultimo il più vecchio (d5). Controlla anche che con l'ordinamento
     * per campo
     * lo score non sia calcolato (NaN): è un comportamento di Lucene, quindi il
     * test
     * documenta un'ipotesi da rivedere se cambia la modalità di ordinamento.
     *
     * @throws Exception se la ricerca fallisce
     */

    @Test
    @Order(8)
    @DisplayName("08 Ordinamento: rilevanza e data")
    void sorting() throws Exception {
        step("Ordinamento per rilevanza e per data (decrescente)");

        // Per rilevanza: gli score devono essere non crescenti
        SearchResponse byScore = search("terremoto calcio", "", null, null, false, 1, 10);
        printResponse("terremoto calcio  [ordinamento: rilevanza]", byScore);
        List<SearchResult> sr = results(byScore);
        boolean scoreDesc = true;
        for (int i = 1; i < sr.size(); i++) {
            scoreDesc &= score(sr.get(i - 1)) >= score(sr.get(i));
        }

        // Per data: tutti gli articoli, dal piu' recente. NB: con l'ordinamento per
        // campo
        // Lucene non calcola lo score (qui risulta NaN)
        SearchResponse byDate = search("", "", null, null, true, 1, 10);
        printResponse("[tutti gli articoli]  [ordinamento: data, piu' recenti prima]", byDate);
        List<SearchResult> dr = results(byDate);
        boolean dateDesc = true;
        for (int i = 1; i < dr.size(); i++) {
            dateDesc &= !date(dr.get(i - 1)).before(date(dr.get(i)));
        }

        final boolean scoreOk = scoreDesc;
        final boolean dateOk = dateDesc;
        assertAll(
                () -> assertTrue(scoreOk, "gli score devono essere in ordine decrescente"),
                () -> assertTrue(dateOk, "le date devono essere in ordine decrescente"),
                () -> assertEquals("Lanciato un nuovo telescopio spaziale", titles(byDate).get(0)),
                () -> assertEquals("Calcio: il campionato di serie B riprende", titles(byDate).get(dr.size() - 1)),
                () -> assertTrue(Float.isNaN(score(dr.get(0))), "ordinando per data lo score non e' calcolato"));
        summary.put("08 Ordinamento: rilevanza e data",
                "score decrescente=" + scoreOk + ", date decrescenti=" + dateOk);
    }

    /**
     * <b>09 - Paginazione.</b> Con 7 articoli e pageSize 3 verifica pagine da 3+3+1
     * risultati,
     * assenza di duplicati o mancanti tra le pagine (ordinamento stabile per data),
     * pagina
     * oltre la fine (lista vuota, {@code totalHits} invariato), pagina 0 trattata
     * come 1 e
     * {@code totalPages} = 3.
     *
     * @throws Exception se la ricerca fallisce
     */

    @Test
    @Order(9)
    @DisplayName("09 Paginazione")
    void pagination() throws Exception {
        step("Paginazione: 7 articoli, 3 per pagina");

        SearchResponse p1 = search("", "", null, null, true, 1, 3);
        SearchResponse p2 = search("", "", null, null, true, 2, 3);
        SearchResponse p3 = search("", "", null, null, true, 3, 3);
        SearchResponse beyond = search("", "", null, null, true, 99, 3);
        SearchResponse zero = search("", "", null, null, true, 0, 3); // pagina < 1 -> trattata come 1

        log("pagina 1: %s", titles(p1));
        log("pagina 2: %s", titles(p2));
        log("pagina 3: %s", titles(p3));
        log("pagina 99 (oltre la fine): %d risultati, totalHits=%d", results(beyond).size(), hits(beyond));
        log("pagina 0 -> trattata come pagina 1: %s", titles(zero));
        log("totalPages dichiarato: %s", val(p1, "totalPages"));

        Set<String> all = new HashSet<>();
        all.addAll(titles(p1));
        all.addAll(titles(p2));
        all.addAll(titles(p3));

        assertAll(
                () -> assertEquals(3, results(p1).size()),
                () -> assertEquals(3, results(p2).size()),
                () -> assertEquals(1, results(p3).size(), "ultima pagina: 7 - 6 = 1 risultato"),
                () -> assertEquals(7, all.size(), "nessun articolo ripetuto o mancante tra le pagine"),
                () -> assertEquals(0, results(beyond).size(), "oltre l'ultima pagina: lista vuota, nessun errore"),
                () -> assertEquals(7, hits(beyond), "totalHits resta il totale reale"),
                () -> assertEquals(titles(p1), titles(zero), "page <= 0 equivale a page 1"),
                () -> assertEquals(3, ((Number) val(p1, "totalPages")).intValue()));
        summary.put("09 Paginazione", "pagine 3+3+1, nessun duplicato, pagina 99 vuota");
    }

    // ------------------------------------------------------------------
    // 6. SNIPPET
    // ------------------------------------------------------------------

    /**
     * <b>10 - Snippet.</b> Con una query testuale verifica che il
     * {@code UnifiedHighlighter}
     * racchiuda i termini trovati in {@code <mark>}. Senza testo (solo categoria)
     * verifica
     * il fallback sull'abstract, senza evidenziazione e con l'HTML neutralizzato
     * ({@code <} diventa {@code &lt;}, {@code &} diventa {@code &amp;}). L'escape
     * protegge
     * da XSS quando lo snippet viene mostrato nella pagina.
     *
     * @throws Exception se la ricerca fallisce
     */
    @Test
    @Order(10)
    @DisplayName("10 Snippet: evidenziazione e fallback con escape")
    void snippets() throws Exception {
        step("Snippet: <mark> sul contenuto, fallback sull'abstract quando non c'e' testo");

        // Con testo: UnifiedHighlighter evidenzia i termini trovati nel contenuto
        SearchResponse withText = search("terremoto");
        String highlighted = snippet(results(withText).get(0));
        log("Query 'terremoto' -> snippet: %s", highlighted);

        // Solo categoria: niente highlighting, si usa l'abstract (con escape dell'HTML)
        SearchResponse onlyCat = search("", "Spazio", null, null, false, 1, 10);
        String fallback = snippet(results(onlyCat).get(0));
        log("Solo categoria 'Spazio' -> snippet di fallback: %s", fallback);

        assertAll(
                () -> assertTrue(highlighted.contains("<mark>"), "i termini trovati devono essere evidenziati"),
                () -> assertFalse(fallback.contains("<mark>"), "il fallback non evidenzia nulla"),
                () -> assertFalse(fallback.contains("<b>"), "l'HTML dell'abstract deve essere neutralizzato"),
                () -> assertTrue(fallback.contains("&lt;b&gt;"), "'<' e '>' diventano entita' HTML"),
                () -> assertTrue(fallback.contains("&amp;"), "'&' diventa '&amp;'"));
        summary.put("10 Snippet: evidenziazione e fallback con escape", "mark OK, escape OK");
    }

    // ------------------------------------------------------------------
    // 7. ERRORI E CASI LIMITE
    // ------------------------------------------------------------------

    /**
     * <b>11 - Errori e casi limite.</b> Query malformate (virgolette o parentesi
     * non chiuse)
     * devono generare {@link ParseException}, che il controller traduce in un
     * messaggio
     * all'utente. Un termine inesistente restituisce 0 risultati senza errori.
     * Testo vuoto
     * senza filtri restituisce tutti i documenti ({@code MatchAllDocsQuery}).
     *
     * @throws Exception se un caso valido solleva un'eccezione
     */
    @Test
    @Order(11)
    @DisplayName("11 Errori e casi limite")
    void errorsAndEdgeCases() throws Exception {
        step("Query non valide e casi limite");

        // Query malformate: SearchService propaga ParseException, che il controller
        // trasforma nel messaggio "Query non valida: controlla virgolette e parentesi."
        assertThrows(ParseException.class, () -> search("\"elezioni europee"), "virgolette non chiuse");
        assertThrows(ParseException.class, () -> search("(elezioni"), "parentesi non chiusa");
        log("Virgolette o parentesi non chiuse -> ParseException (come previsto)");

        // Nessun risultato
        SearchResponse none = search("xyzzynonesistente");
        log("Termine inesistente -> %d risultati", hits(none));

        // Testo vuoto e nessun filtro: MatchAllDocsQuery -> tutti i documenti
        SearchResponse all = search("");
        log("Testo vuoto senza filtri -> %d risultati (tutti i documenti)", hits(all));

        assertAll(
                () -> assertEquals(0, hits(none)),
                () -> assertTrue(results(none).isEmpty()),
                () -> assertEquals(SAMPLE.size(), hits(all)));
        summary.put("11 Errori e casi limite", "2 ParseException, 0 risultati, tutti=" + hits(all));
    }

    // ------------------------------------------------------------------
    // 8. AGGIORNAMENTO DELL'INDICE
    // ------------------------------------------------------------------

    /**
     * <b>12 - Refresh del SearcherManager.</b> Usa un indice separato con solo d1:
     * "elezioni"
     * dà 0 risultati. Dopo aver aggiunto d2 senza riavviare né fare commit, la
     * stessa
     * ricerca ne dà 1, perché {@code ArticleSearcher} chiama
     * {@code maybeRefresh()}.
     * Verifica che i nuovi documenti diventino ricercabili a runtime.
     *
     * @throws Exception se l'indice o la ricerca falliscono
     */
    @Test
    @Order(12)
    @DisplayName("12 Refresh del SearcherManager")
    void searcherRefresh() throws Exception {
        step("Un documento aggiunto all'indice diventa ricercabile senza riavviare");

        // Indice separato, cosi' gli altri test non vengono influenzati
        try (Fixture own = new Fixture(List.of(SAMPLE.get(0)))) { // solo d1
            long before = hitsOf(own, "elezioni");
            own.add(SAMPLE.get(1)); // aggiungo d2 (elezioni europee)
            long after = hitsOf(own, "elezioni");
            log("Risultati per 'elezioni' prima: %d, dopo l'aggiunta di un articolo: %d", before, after);

            assertEquals(0, before);
            assertEquals(1, after, "acquireSearcher() chiama maybeRefresh(): il nuovo documento e' visibile");
            summary.put("12 Refresh del SearcherManager", before + " -> " + after + " risultati dopo l'aggiunta");
        }
    }

    // ------------------------------------------------------------------
    // 9. PRESTAZIONI
    // ------------------------------------------------------------------
    /**
     * <b>13 - Prestazioni (indice dummy da 7 documenti).</b> Per ogni query esegue
     * 20 ricerche
     * di riscaldamento (JIT e cache) e 200 ricerche misurate con
     * {@code System.nanoTime()}.
     * Metriche stampate, in millisecondi:
     * <ul>
     * <li><b>hit</b>: numero di risultati, per accorgersi di query che non trovano
     * nulla;</li>
     * <li><b>min / max</b>: caso migliore e peggiore (il max è sensibile a GC e
     * scheduling);</li>
     * <li><b>media</b>: latenza media;</li>
     * <li><b>p95</b>: il 95% delle ricerche è stato più veloce di questo valore, e
     * rappresenta meglio dei picchi l'esperienza tipica dell'utente;</li>
     * <li><b>throughput</b>: ricerche totali / somma delle latenze. Poiché le
     * ricerche sono
     * sequenziali su un solo thread, vale circa 1/media. Non è la capacità sotto
     * carico
     * concorrente.</li>
     * </ul>
     * L'assert fallisce solo se la media peggiore supera 200 ms, soglia molto
     * larga. Misura
     * l'overhead fisso del sistema, non la scalabilità: per quella vedi i test 14 e
     * 15.
     *
     * @throws Exception se una ricerca fallisce
     */
    @Test
    @Order(13)
    @DisplayName("13 Prestazioni: latenza e throughput")
    void performance() throws Exception {
        step("Prestazioni: 200 esecuzioni per query dopo 20 di riscaldamento");

        final int warmup = 20;
        final int runs = 200;
        String[] queries = { "terremoto", "\"elezioni europee\"", "+calcio -serie", "categories:sport", "elezioni" };

        System.out.printf("%-22s %6s %10s %10s %10s %10s%n", "query", "hit", "min(ms)", "media(ms)", "p95(ms)",
                "max(ms)");
        double worstAvg = 0;
        long totalRuns = 0;
        long totalNanos = 0;

        for (String q : queries) {
            for (int i = 0; i < warmup; i++) { // riscaldamento (JIT, cache)
                search(q);
            }
            long[] nanos = new long[runs];
            long hits = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                SearchResponse r = search(q);
                nanos[i] = System.nanoTime() - t0;
                hits = hits(r);
            }
            Arrays.sort(nanos);
            double avg = Arrays.stream(nanos).average().orElse(0) / 1e6;
            double p95 = nanos[(int) Math.ceil(0.95 * runs) - 1] / 1e6;
            System.out.printf("%-22s %6d %10.3f %10.3f %10.3f %10.3f%n",
                    q, hits, nanos[0] / 1e6, avg, p95, nanos[runs - 1] / 1e6);

            worstAvg = Math.max(worstAvg, avg);
            totalRuns += runs;
            totalNanos += Arrays.stream(nanos).sum();
        }

        double qps = totalRuns / (totalNanos / 1e9);
        log("Throughput complessivo: %.0f ricerche/secondo (%d ricerche)", qps, totalRuns);

        // Soglia volutamente larga per non rendere il test instabile su macchine lente
        final double worst = worstAvg;
        assertTrue(worst < 200, "la latenza media non dovrebbe superare 200 ms, trovato " + fmt(worst));
        summary.put("13 Prestazioni: latenza e throughput",
                String.format("peggior media %.3f ms, ~%.0f ricerche/s", worst, qps));
    }

    // ------------------------------------------------------------------
    // METODI DI SUPPORTO
    // ------------------------------------------------------------------

    /**
     * Esegue una ricerca con tutti i parametri (le date in formato "yyyy-MM-dd").
     */
    private SearchResponse search(String q, String category, String from, String to,
            boolean byDate, int page, int pageSize) throws Exception {
        return fx.service.search(q, category,
                from == null ? null : LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to),
                byDate, page, pageSize);
    }

    /** Ricerca semplice: solo testo, per rilevanza, prima pagina da 10. */
    private SearchResponse search(String q) throws Exception {
        return search(q, "", null, null, false, 1, 10);
    }

    /**
     * Numero di risultati di una ricerca testuale su un indice diverso da quello
     * condiviso.
     */
    private static long hitsOf(Fixture f, String q) throws Exception {
        return hits(f.service.search(q, "", null, null, false, 1, 10));
    }

    /** Token prodotti dall'analyzer dell'applicazione per un certo campo. */
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

    // --- Accesso a SearchResponse / SearchResult --------------------------------
    // Non conosco se queste classi usino record (title()) o getter (getTitle()):
    // l'helper val() prova entrambi. Se sono record puoi sostituire val(...) con le
    // chiamate dirette.

    private static Object val(Object o, String prop) {
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (String name : new String[] { prop, "get" + cap, "is" + cap }) {
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

    private static List<String> titles(SearchResponse r) {
        return results(r).stream().map(x -> String.valueOf(val(x, "title"))).toList();
    }

    private static String snippet(SearchResult r) {
        return String.valueOf(val(r, "snippet"));
    }

    private static Date date(SearchResult r) {
        return (Date) val(r, "date");
    }

    private static float score(SearchResult r) {
        return ((Number) val(r, "score")).floatValue();
    }

    // --- Stampa
    // -------------------------------------------------------------------

    private static String fmt(double d) {
        return String.format("%.4f", d);
    }

    private static void log(String format, Object... args) {
        System.out.println("  " + String.format(format, args));
    }

    private static void step(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    /** Stampa una risposta: query, totale, tempo, pagina e righe dei risultati. */
    private static void printResponse(String label, SearchResponse r) {
        System.out.printf("  Query: %s%n", label);
        System.out.printf("  -> totalHits=%d, took=%s ms, pagina=%s/%s%n",
                hits(r), val(r, "tookMs"), val(r, "page"), val(r, "totalPages"));
        int i = 1;
        for (SearchResult x : results(r)) {
            Date d = date(x);
            System.out.printf("     %d. [score=%-8s] [%s] %s  %s%n", i++,
                    Float.isNaN(score(x)) ? "n/d" : String.format("%.4f", score(x)),
                    d == null ? "senza data" : d.toInstant().toString().substring(0, 10),
                    val(x, "title"), val(x, "categories"));
        }
    }

    /**
     * Riepilogo finale: una riga per test con la metrica principale e la durata.
     */
    private void printSummary() {
        System.out.println("\n================ RIEPILOGO ================");
        System.out.printf("%-52s %8s  %s%n", "Test", "durata", "risultato/metrica");
        for (Map.Entry<String, String> e : summary.entrySet()) {
            System.out.printf("%-52s %6d ms  %s%n", e.getKey(), durationsMs.getOrDefault(e.getKey(), -1L),
                    e.getValue());
        }
        System.out.printf("Test eseguiti: %d, tempo totale: %d ms%n",
                durationsMs.size(), durationsMs.values().stream().mapToLong(Long::longValue).sum());
        System.out.println("===========================================\n");
    }
}