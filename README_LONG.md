# Wikinews IT Search — stato del progetto

File di lavoro del gruppo: obiettivi, cosa è fatto, cosa resta da fare, problemi noti.
Il README per chi installa e usa il sistema è `README.MD`.
Le note sulla valutazione (metriche, ablation, errori da evitare) sono in `TEST_NOTES.md`.

**Come aggiornarlo:** quando finisci un punto, spuntalo (`- [x]`), spostalo in "Fatto" con il branch
o il commit, e aggiungi una riga al [registro delle modifiche](#registro-delle-modifiche).
Se scopri un problema, aggiungilo in [Problemi noti](#problemi-noti).

Ultimo aggiornamento: 2026-11-18

---

## Obiettivi (traccia dell'homework, `PREREQUISITES.md`)

1. Motore di ricerca con interfaccia Web sulle notizie di it.wikinews.org (dump Enterprise HTML del 20/03/2025).
2. Strategia di test e valutazione del sistema.
3. Codice su GitHub con istruzioni di installazione e uso.

### Dati del dump (verificati)

| | |
|---|---|
| Righe (articoli) | 12.261 |
| Id distinti | 12.213 (48 articoli compaiono due volte; la seconda copia è sempre più recente) |
| Articoli senza `abstract` | 266 |
| Articoli senza categorie | 23 |
| Categorie distinte | 5.964 |

---

## Fatto

### Indicizzazione
- [x] Modelli `Article`, `Category` e lettura del dump NDJSON (`JsonReader`, `ArticleParser` con Jsoup)
- [x] Analyzer: `ItalianAnalyzer` per i testi, analyzer dedicato per le categorie (`AnalyzerFactory`)
- [x] Indicizzazione all'avvio (`IndexStartup`, `ImportService`, `ArticleIndexer`)
- [x] Campi: `id`, `url`, `name`, `abstract`, `content`, `categories`, `date_modified` (punto, stored, doc values)
- [x] Articoli con campi mancanti indicizzati senza errori (stringa vuota; data opzionale)
- [x] Duplicati: `updateDocument` sull'id, resta una sola copia (l'ultima, la più recente)
- [x] Log con articoli importati, scartati e documenti nell'indice

### Ricerca
- [x] `SearchService`, `QueryFactory`, `ArticleSearcher` (package `lucene/search`)
- [x] Ricerca multi-campo con boost: titolo 3, abstract 2, contenuto 1, categorie 1
- [x] Operatore AND di default
- [x] Boost di prossimità (parole vicine, slop 3, boost 2)
- [x] Filtri per categoria e per intervallo di date; ordinamento per rilevanza o per data
- [x] Senza testo (solo filtri) ordina per data
- [x] Numero esatto di risultati (`searcher.count`, non più fermo a 1000)
- [x] Snippet con evidenziazione (`UnifiedHighlighter`) e fallback con escape HTML
- [x] Suggerimenti "Forse cercavi…" (`QuerySuggestionService`, `lucene-suggest`)
- [x] `SearchResult` contiene l'`id` dell'articolo (serve per la valutazione)

### Interfaccia Web
- [x] Pagine Thymeleaf `home.html`, `search.html` con fragment `head`, `header`, `searchBar`, `footer`
- [x] Paginazione (10 risultati per pagina), categorie cliccabili nei risultati
- [x] Autocompletamento del campo categoria: `CategoryService` + `GET /categories` + `static/js/category-autocomplete.js`
      (prima i nomi che iniziano con il testo, poi le parole che iniziano con il testo, poi il resto)
- [x] HTML, CSS e JavaScript in file separati

### Test e valutazione
- [x] 13 test funzionali su indice in memoria (`WikinewsApplicationTests`): analyzer, boost, sintassi, filtri,
      ordinamento, paginazione, snippet, casi limite, latenza (min/media/p95/max) e throughput
- [x] Test di latenza sull'indice dell'applicazione (`RealIndexPerformanceTest`, 21 query)
- [x] `IrMetrics`: Precision@K, Recall@K, Reciprocal Rank, Average Precision, nDCG@K
- [x] `IrMetricsTest`: le formule verificate su esempi calcolati a mano (8 test)

### Consegna
- [x] `README.MD` iniziale, script `start.sh` / `start.ps1`

---

## Da fare

### 1. Metriche di valutazione
- [x] 28 query con bisogno informativo in `src/test/resources/evaluation/queries.tsv` (`qid`, `query`, `need`)
- [x] `PoolBuilderTest`: per ogni query scrive i primi 20 risultati in `target/evaluation/pool.tsv` (530 righe)
- [ ] **Giudicare il pool**: colonna `relevance` con 0/1/2 leggendo il `need` della query, poi salvare il file come
      `src/test/resources/evaluation/qrels.tsv` (vedi [Come giudicare il pool](#come-giudicare-il-pool))
- [x] **Valutazione automatica known-item** (`KnownItemEvaluationTest`): 500 articoli scelti a caso (seme fisso)
      cercano se stessi, con query dal titolo e con 4 parole del testo non presenti nel titolo.
      Lancio: `./mvnw test -Dtest=KnownItemEvaluationTest -DexcludedGroups=none` (circa 40 s).
      Dettaglio per query in `target/evaluation/known-item.tsv`
- [ ] Valutazione automatica con le categorie come giudizi (escludendo il campo `categories` dalla ricerca)
- [ ] `RetrievalEvaluationTest`: calcola P@10, MRR, MAP, nDCG@10 sull'indice completo e salva la tabella
- [x] Tag JUnit `evaluation` per i test sull'indice reale, esclusi dal normale `./mvnw test`
- [ ] Mettere `@Tag("evaluation")` anche su `RealIndexPerformanceTest`
- [ ] Ampliare il pool con i risultati delle varianti dell'ablation (oggi viene da una sola configurazione)
- [ ] Efficienza dell'indicizzazione: articoli al secondo e dimensione dell'indice su disco
- [ ] Copertura: test automatico righe del dump = articoli indicizzati, documenti = id distinti
- [ ] Accuratezza dei suggerimenti: coppie refuso → correzione attesa, più query corrette che non devono avere suggerimenti

#### Come giudicare il pool
1. Generare il pool (circa 30 secondi, indicizza tutto il dump in `target/evaluation-index`):
   `./mvnw test -Dtest=PoolBuilderTest -DexcludedGroups=none`
2. Aprire `wikinews/target/evaluation/pool.tsv` (va bene anche un foglio di calcolo, salvando in TSV).
3. Per ogni riga leggere il titolo (e l'articolo dall'`url`, se serve) e scrivere in `relevance`:
   **0** = non risponde al bisogno, **1** = in parte, **2** = risponde pienamente.
   Il criterio è il `need` della query in `queries.tsv`, non le parole della query.
4. Dividere le query fra noi (per esempio q01–q10, q11–q19, q20–q28) e giudicare in modo indipendente.
5. Salvare il risultato come `wikinews/src/test/resources/evaluation/qrels.tsv`, senza cambiare le colonne.

Il pool va rigenerato solo se cambiano le query: rigenerandolo i giudizi già scritti andrebbero persi.

#### Risultati known-item (configurazione attuale, 2026-11-18)

| Query | N. query | MRR | Success@1 | Success@10 | Non trovati (top 100) |
|---|---|---|---|---|---|
| Titolo | 500 | 0,959 | 0,938 | 0,996 | 0 |
| 4 parole del testo | 429 | 0,830 | 0,795 | 0,893 | 20 |

- 71 articoli non hanno abbastanza parole adatte nell'abstract per la seconda variante.
- Quasi tutti i non trovati sono cronache sportive con lo stesso schema ("Serie A 2011-2012: 35ª giornata"):
  le parole scelte ("classifica", "risultati", "giornata") compaiono in centinaia di articoli quasi identici,
  quindi la query è ambigua anche per un umano. È un limite del metodo, non solo del motore.

### 2. Ablation study e verifica delle scelte
- [x] Boost dei campi e prossimità configurabili (`SearchSettings`, proprietà `wikinews.search.*`), con `QueryFactorySettingsTest`
- [x] Operatore AND/OR configurabile (`wikinews.search.and-operator`)
- [ ] Confrontare, una scelta alla volta:
  - [x] boost del titolo 3 contro 1 (risultati in `TEST_NOTES.md`)
  - [x] boost dei campi 3/2/1/1 contro tutti a 1 (known-item)
  - [x] AND contro OR con il known-item (risultati in `TEST_NOTES.md`; da confermare per argomento)
  - [x] prossimità attiva / disattivata (known-item: effetto nullo, il metodo non la misura bene)
  - [x] categorie incluse o escluse dalla ricerca libera (known-item: effetto nullo)
  - [ ] ripetere boost, prossimità e categorie con la valutazione per argomento (giudizi manuali)
  - [ ] BM25 contro TF-IDF (`ClassicSimilarity`)
  - [ ] `ItalianAnalyzer` contro `StandardAnalyzer` (serve un secondo indice)
  - [ ] `SimpleTextCodec` contro codec di default (tempo e dimensione dell'indice)
- [ ] Scegliere la configurazione su metà delle query e riportare i risultati sull'altra metà (evita l'overfitting)
- [ ] Analisi degli errori: 2–3 query in cui il sistema sbaglia, con il motivo

### 3. Front end
- [ ] Nascondere le categorie tecniche ("Pubblicati", "Articoli archiviati", "Articoli da controllare per l'archiviazione", date)
- [ ] Stato "nessun risultato" curato (suggerimento, invito a togliere i filtri)
- [ ] Titolo evidenziato, data in formato italiano
- [ ] Paginazione con numeri di pagina
- [ ] Layout mobile e accessibilità

### 4. README finale
- [ ] Descrizione e architettura (dump → parser → analyzer → indice → ricerca → UI)
- [ ] Scelte progettuali motivate (campi, analyzer, BM25, boost, AND, prossimità, suggerimenti)
- [ ] Installazione: Java 21, download ed estrazione del dump, avvio, come reindicizzare
- [ ] Esempi di query (frase, `+`/`-`, `categories:`, filtri)
- [ ] Come è stato testato e tabelle dei risultati (efficienza, efficacia, ablation)
- [ ] Limiti e sviluppi futuri

---

## Problemi noti

| Problema | Dove | Effetto |
|---|---|---|
| 4 test falliscono: `SearchService` creato con `null` al posto di `QuerySuggestionService` | `WikinewsApplicationTests.java:182` | `luceneSyntax`, `sorting`, `errorsAndEdgeCases`, `searcherRefresh` vanno in `NullPointerException` |
| `RealIndexPerformanceTest` misura un indice di soli 100 articoli | `@SpringBootTest` usa `maxArticle=99` | tempi e numeri di risultati non rappresentativi; anche la tabella nel `README.MD` |
| `SimpleTextCodec` e `OpenMode.CREATE` | `LuceneManager` | indice lento e grande, ricostruito a ogni avvio |
| `maxArticle` sbagliato di uno e il file viene letto tutto anche oltre il limite | `ImportService` | con `maxArticle=99` si indicizzano 100 articoli |
| Il filtro categoria è una frase sul campo analizzato | `QueryFactory.addCategoryFilter` | "Italia" trova anche "Calcio in Italia"; serve un campo con una categoria per valore (reindicizzazione) |
| Dipendenze `spring-boot-starter-web` e `-webmvc` entrambe presenti | `pom.xml` | ridondanza |

---

## Registro delle modifiche

| Data | Branch | Modifica |
|---|---|---|
| 2026-11-19 | `dev/adducec` | Ablation known-item: tutti i boost a 1, senza prossimità, senza categorie |
| 2026-11-19 | `dev/adducec` | Operatore AND/OR configurabile, ablation AND contro OR |
| 2026-11-19 | `dev/adducec` | Boost configurabili (`SearchSettings`), `QueryFactorySettingsTest`, prima ablation sul boost del titolo |
| 2026-11-18 | `dev/adducec` | Creato `TEST_NOTES.md` (strategia, metriche, piano di ablation) |
| 2026-11-18 | `dev/adducec` | Valutazione automatica known-item (`KnownItemEvaluationTest`) |
| 2026-11-18 | `dev/adducec` | 28 query di valutazione, `PoolBuilderTest`, tag `evaluation` escluso di default nel `pom.xml` |
| 2026-11-18 | `dev/adducec` | `id` in `SearchResult`; `IrMetrics` e `IrMetricsTest`; creato questo file |
| 2026-11-18 | `dev/adducec` | Indicizzazione: campi mancanti e duplicati (commit `fe1b11d`) |
| — | `dev/hb-suggestions` | Suggerimenti "Forse cercavi…" |
| — | `dev/adducec` | Autocompletamento delle categorie |
| — | `dev/shiko` | Test funzionali e di prestazioni |
| — | `dev/adducec` | AND di default, boost dei campi, prossimità, conteggio esatto, ordinamento per data senza testo |
