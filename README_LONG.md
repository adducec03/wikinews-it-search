# Wikinews IT Search — stato del progetto

File di lavoro del gruppo: obiettivi, cosa è fatto, cosa resta da fare, problemi noti.
Il README per chi installa e usa il sistema è `README.MD`.

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
- [ ] Scrivere 20–30 query di tipi diversi (nomi propri, argomenti, frasi, refusi, filtri) in
      `src/test/resources/evaluation/queries.tsv` (`id_query`, `testo`)
- [ ] `PoolBuilderTest`: per ogni query scrive i primi 20 risultati in `target/pool.tsv` da giudicare a mano
- [ ] Giudizi di rilevanza (0/1/2) in `src/test/resources/evaluation/qrels.tsv` (`id_query`, `id_articolo`, `rilevanza`)
      — usare l'`identifier` dell'articolo, non il docId di Lucene
- [ ] `RetrievalEvaluationTest`: calcola P@10, MRR, MAP, nDCG@10 sull'indice completo e salva la tabella
- [ ] Tag JUnit `evaluation` per i test sull'indice reale, esclusi dal normale `./mvnw test`
- [ ] Efficienza dell'indicizzazione: articoli al secondo e dimensione dell'indice su disco
- [ ] Copertura: test automatico righe del dump = articoli indicizzati, documenti = id distinti
- [ ] Accuratezza dei suggerimenti: coppie refuso → correzione attesa, più query corrette che non devono avere suggerimenti

### 2. Ablation study e verifica delle scelte
- [ ] Rendere configurabili boost, AND/OR e prossimità (oggi costanti in `QueryFactory`), per provarli senza modificare il codice
- [ ] Confrontare, una scelta alla volta:
  - [ ] boost dei campi 3/2/1/1 contro tutti a 1
  - [ ] AND contro OR
  - [ ] prossimità attiva / disattivata / slop diversi
  - [ ] categorie incluse o escluse dalla ricerca libera
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
| 2026-11-18 | `dev/adducec` | `id` in `SearchResult`; `IrMetrics` e `IrMetricsTest`; creato questo file |
| 2026-11-18 | `dev/adducec` | Indicizzazione: campi mancanti e duplicati (commit `fe1b11d`) |
| — | `dev/hb-suggestions` | Suggerimenti "Forse cercavi…" |
| — | `dev/adducec` | Autocompletamento delle categorie |
| — | `dev/shiko` | Test funzionali e di prestazioni |
| — | `dev/adducec` | AND di default, boost dei campi, prossimità, conteggio esatto, ordinamento per data senza testo |
