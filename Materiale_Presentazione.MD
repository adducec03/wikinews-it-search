# Wikinews IT Search: materiale per relazione e presentazione (HW4)

Documento di lavoro del 19/11/2026. Raccoglie tutto ciò che si può dire in relazione e in presentazione:
com'è fatto il sistema, che test facciamo, cosa significano i numeri e quali conclusioni se ne traggono.

**Fonte dei numeri:** esecuzione completa dei test (Java 21.0.9, Spring Boot 4.1.2, Linux).
Hardware: `[CPU / RAM da completare]`.
Dove utile sono riportati, a confronto, i valori misurati dal collega su Apple M1 (da `TEST_NOTES.md`).

**Regola d'oro:** nei tempi (ms, secondi) usare una sola macchina per tutta la relazione e dichiararla.
Le dimensioni dell'indice e i valori di efficacia (MRR, P@10, ...) sono invece deterministici e uguali su ogni macchina.

Legenda: ✅ numero affidabile · ⚠️ preliminare, da rieseguire sull'indice completo (vedi sezione 6).

---

## 0. Mappa: traccia dell'homework → dove trovare cosa

| Richiesta dell'homework | Sezione |
|---|---|
| Sfide tecniche e metodologiche | 1.4, 7 |
| Soluzioni adottate (no codice) | 1.2, 1.3, 8 |
| Strategia di test | 3 |
| Risultati sperimentali che supportano le scelte | 4, 5 |
| Limiti | 6 |
| Scaletta delle slide (20 minuti) | 9 |
| Domande probabili | 10 |
| Comandi per riprodurre | 11 |

---

## 1. Il sistema in breve

### 1.1 I dati

| | |
|---|---|
| Dump | `itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz` (estratto: NDJSON, 564 MB) |
| Righe (articoli) | 12.261 |
| Id distinti = documenti nell'indice | 12.213 |
| Duplicati | 48 articoli compaiono due volte; resta la copia più recente |
| Articoli senza abstract / senza categorie | 266 / 23 (indicizzati comunque, senza errori) |
| Categorie distinte | 5.964 |

### 1.2 Pipeline

```
dump NDJSON ──► JsonReader ──► ArticleParser (Jsoup: HTML → testo) ──► ArticleIndexer ──► indice Lucene
                                                                                              │
browser ──► SearchController ──► SearchService ──► QueryFactory ──► ArticleSearcher ◄────────┘
   ▲                                  │                                  (SearcherManager)
   └────── pagina Thymeleaf ◄─────────┴── snippet (UnifiedHighlighter) + "Forse cercavi…" (lucene-suggest)
```

- **All'avvio** l'applicazione legge il dump e costruisce l'indice (log: articoli importati, scartati, documenti nell'indice).
- **A ogni ricerca** `SearchService` costruisce la query con lo *stesso* analyzer usato in indicizzazione,
  la esegue, calcola gli snippet e restituisce una pagina di 10 risultati.

### 1.3 Scelte progettuali e motivazioni

| Scelta | Perché |
|---|---|
| **Apache Lucene** (libreria di IR) | indice invertito, ranking BM25, query booleane/frase/campo già pronti; è lo standard visto a lezione |
| **Campi separati**: `name`, `abstract`, `content`, `categories`, `url`, `id`, `date_modified` | si possono pesare in modo diverso (un match nel titolo vale più di uno nel testo) e filtrare per categoria e data |
| **`ItalianAnalyzer`** sui testi | tokenizzazione, minuscole, elisioni ("l'Abruzzo"), stop word e **stemming** italiano: "elezione" e "elezioni" diventano `elezion` |
| **Analyzer dedicato sulle categorie** (tokenizer standard, minuscole, stop word con in più "articoli" e "pubblicati") | le categorie sono etichette, non frasi: niente stemming; "Pubblicati" è una categoria tecnica che non deve produrre match |
| **Boost sui campi**: titolo 3, abstract 2, contenuto 1, categorie 1 | il titolo è il campo più informativo |
| **Operatore AND di default** | precisione: una query di più parole deve trovare articoli che le contengono tutte (si può scrivere `OR` esplicitamente) |
| **Boost di prossimità** (parole entro 3 posizioni, boost 2) | i concetti di più parole ("incidente ferroviario") devono battere articoli che hanno le parole sparse |
| **Filtri come clausole che non cambiano lo score** (categoria, intervallo di date) | restringono senza alterare il ranking |
| **Data in 3 forme** (punto numerico, valore memorizzato, doc values) | ognuna serve a una cosa: filtrare per intervallo, mostrare la data, ordinare per data |
| **Ordinamento**: rilevanza oppure data; senza testo si ordina per data | senza testo tutti i punteggi sarebbero uguali e l'ordine casuale |
| **Duplicati**: `updateDocument` sull'id | Lucene è append-only: "aggiornare" = cancellare e riaggiungere |
| **`SearcherManager`** | i nuovi documenti diventano visibili senza riavviare |
| **Snippet con `UnifiedHighlighter`** (`<mark>`) e fallback sull'abstract con escape HTML | l'utente vede *perché* l'articolo è stato trovato; l'escape evita XSS |
| **"Forse cercavi…"** (correzione ortografica, `lucene-suggest`) quando i risultati sono 0 | recupera i refusi |
| **Autocompletamento delle categorie** | le categorie sono quasi 6.000 |

### 1.4 Sfide tecniche e metodologiche (da raccontare)

1. **Dati sporchi:** HTML da trasformare in testo, 48 duplicati, campi mancanti → il sistema non deve scartare né rompersi.
2. **Lingua italiana:** serve stemming e stop word, ma non sulle categorie.
3. **Come decidere i parametri** (boost, AND/OR, prossimità) senza giudizi già pronti → costruire una valutazione.
4. **Misurare la qualità senza un benchmark**: nessun insieme di query con giudizi di rilevanza esiste per questo dump.
   Soluzione a due livelli: valutazione automatica (known-item) e giudizi manuali su un pool.
5. **Misurare le prestazioni in modo onesto:** riscaldamento della JVM, variabilità, indice completo e non di prova.

---

## 2. Glossario: cosa vuol dire ogni termine

**Indice invertito.** Struttura che per ogni *termine* elenca i documenti che lo contengono (*postings*). È ciò che rende la ricerca veloce.

**Analyzer.** Trasforma un testo in termini: tokenizer (spezza) + filtri (minuscole, stop word, stemming).
*Deve essere lo stesso in indicizzazione e in ricerca*, altrimenti i termini cercati non coincidono con quelli indicizzati.

**Stemming.** Riduce le parole alla radice ("elezioni" → `elezion`). Aumenta la *recall*, può ridurre la precisione.

**Stop word.** Parole molto frequenti e poco informative ("il", "di") che si scartano.

**BM25.** Funzione di ranking di default di Lucene: più un termine è frequente *nel documento* e raro *nella collezione*, più il documento è rilevante;
due parametri: `k1` (quanto saturano le occorrenze ripetute) e `b` (quanto pesa la lunghezza del documento). Evoluzione del TF-IDF.

**Boost.** Moltiplicatore che dà più peso a un campo o a una clausola.

**Prossimità (slop).** Le parole della query sono premiate se compaiono vicine nel testo; slop 3 = al massimo 3 posizioni di distanza.

**Segmento.** Un indice Lucene è fatto di segmenti, mini-indici immutabili. `commit` li rende durevoli, il *refresh* del `SearcherManager` li rende visibili alla ricerca.

**Codec.** Il codec decide **in che formato Lucene scrive su disco** le strutture dell'indice
(dizionario dei termini, postings, campi memorizzati, valori numerici).
- Il **codec di default** usa formati binari compressi e pensati per la velocità (blocchi di docID codificati come differenze, skip list, compressione dei campi memorizzati).
- **`SimpleTextCodec`** scrive tutto in **testo leggibile**: utile per *guardare dentro* l'indice a scopo di debug o di lezione, ma non è compresso né ottimizzato.
- Si imposta con `IndexWriterConfig.setCodec(...)`. Nel progetto `LuceneManager` usa oggi `SimpleTextCodec`.
- Cosa si fa nel test: si costruisce **lo stesso indice due volte**, una per codec, e si confrontano tempo di indicizzazione, dimensione su disco e latenza di ricerca.
  I documenti sono gli stessi (12.213) e i risultati delle ricerche identici: cambia solo il costo.

**Percentili.** *Mediana* = metà delle ricerche è più veloce di questo valore. *p95* = il 95% delle ricerche è più veloce di questo valore.
Il p95 rappresenta l'esperienza tipica dell'utente meglio della media, che i picchi (garbage collector) falsano.

**Riscaldamento (warm-up).** La prima esecuzione è lenta: la JVM compila il codice (JIT), carica classi, riempie le cache.
Si misura *dopo* il riscaldamento e si riporta a parte la prima volta.

**Throughput.** Ricerche al secondo su un thread ≈ 1000 / latenza media (ms). Non è la capacità sotto carico concorrente.

**Precision@K.** Dei primi K risultati, quanti sono rilevanti. Si divide sempre per K: se una query ha solo 3 rilevanti in tutto, P@10 ≤ 0,3.

**Recall@K.** Dei rilevanti esistenti, quanti compaiono nei primi K.

**RR / MRR.** 1 / posizione del primo risultato rilevante, mediato sulle query. Vale 1 se il primo è sempre giusto.
Guarda solo il primo rilevante.

**AP / MAP.** Media delle precisioni nei punti in cui compare un rilevante, poi media sulle query. Premia ordine *e* completezza.

**nDCG@K.** Come MAP ma con voti graduati (0/1/2): un rilevante in alto vale più che in basso; 1 = ordinamento ideale.

**Success@K.** 1 se l'articolo cercato è nei primi K risultati, altrimenti 0; media sulle query.

**Known-item search.** Si prende un articolo, si usa il suo titolo (o 4 parole del suo testo) come query, e si guarda in che posizione ricompare.
Giudizi gratuiti e automatici, ma misura il *ritrovare un articolo noto*, non il *trovare tutto su un argomento*.

**Pooling.** Si uniscono i primi 10 risultati di *tutte* le configurazioni confrontate in un unico "pool" e si giudica solo quello.
Gli articoli non giudicati contano come non rilevanti, quindi la recall è relativa ai rilevanti *conosciuti*.

**Ablazione.** Si cambia **una sola scelta alla volta**, tenendo fermo tutto il resto, e si misura quanto peggiorano (o migliorano) le metriche.
Se togliere una scelta non cambia nulla, quella scelta è una complicazione inutile.

**Test di invarianti (metamorfici).** Non si controlla un valore atteso, ma una *relazione* che deve valere sempre tra i risultati di query diverse
(esempio: aggiungere un filtro non può aumentare i risultati).

---

## 3. Strategia di test: quattro livelli

| Livello | Domanda | Classe di test | Tipo |
|---|---|---|---|
| **Correttezza** | ogni componente fa ciò che deve? | `WikinewsApplicationTests` (13 test) | unitari/funzionali su indice **in memoria** con 7 articoli inventati: risultati attesi noti a priori |
| **Copertura** | l'indicizzazione perde articoli? | log di `ImportService`, `EfficiencyEvaluationTest` | confronto righe del dump / articoli indicizzati / documenti |
| **Invarianti** | le relazioni logiche tra le query valgono sempre? | test 02–05 dei test sull'indice reale | metamorfici, indice completo |
| **Efficienza** | quanto è veloce, quanto occupa? | `RealIndexPerformanceTest`, `EfficiencyEvaluationTest`, test 13 | benchmark con riscaldamento e ripetizioni |
| **Efficacia** | i risultati sono pertinenti? | `KnownItemEvaluationTest`, `PoolBuilderTest` + `RetrievalEvaluationTest`, ablazioni | metriche IR (MRR, MAP, nDCG, ...) |

Perché indice di prova *e* indice reale: sul piccolo indice i risultati attesi si calcolano a mano e il test dura millisecondi (si individuano i bug di logica);
sul reale si misurano prestazioni e qualità vere (scala di 12.213 documenti).

---

## 4. Risultati

### 4.1 Correttezza: 13/13 test superati (3,4 s in tutto)

Indice di prova di 7 articoli costruito a mano con gli stessi componenti dell'applicazione.

| # | Cosa verifica | Esito |
|---|---|---|
| 01 | l'indice contiene i 7 articoli | 7 documenti; token indicizzati: name 28, abstract 43, content 67, categories 14 |
| 02 | analyzer | `elezioni` e `elezione` → `elezion`; `il terremoto` → `terremot` (stop word tolta); categoria `Elezioni` → `elezioni` (niente stemming); `Articoli pubblicati Sport` → `sport` (stop word personalizzate tolte) |
| 03 | boost dei campi | "terremoto": 2 risultati, punteggio **3,33** per chi ha la parola nel titolo e nel contenuto contro **0,59** per chi l'ha solo nel contenuto |
| 04 | stemming in query | "elezione" trova i 2 articoli con "elezioni" |
| 05 | sintassi Lucene | frase esatta → 1; `+calcio -serie` → 1; `categories:sport` → 2; OR esplicito → 4 |
| 06 | filtro categoria | Calcio 2, Politica 2, Roma 1; una categoria con caratteri speciali (`sport) OR (`) è neutralizzata: 0 risultati e nessun errore (nessuna "iniezione" nella query) |
| 07 | filtro date, estremi inclusi | dal 2024-01-01: 5; fino al 2023-12-31: 2; singolo giorno: 1 (anche se l'articolo è alle 15:30); futuro: 0 |
| 08 | ordinamento | punteggi in ordine decrescente; per data: il più recente primo; con ordinamento per data il punteggio non è calcolato (NaN) |
| 09 | paginazione | 7 articoli, 3 per pagina → 3+3+1 senza duplicati; pagina 99 vuota con `totalHits` = 7; pagina 0 trattata come 1 |
| 10 | snippet | `<mark>` sul termine trovato; fallback con `<` → `&lt;` e `&` → `&amp;` (protezione da XSS) |
| 11 | errori e casi limite | virgolette o parentesi non chiuse → `ParseException`; termine inesistente → 0; testo vuoto → tutti i 7 |
| 12 | refresh | "elezioni": 0 risultati; dopo aver aggiunto un articolo senza riavviare: 1 |
| 13 | prestazioni dell'overhead fisso | 5 query × 200 esecuzioni dopo 20 di riscaldamento: medie 0,3–3,7 ms, circa 477 ricerche/s |

Nota sul test 13: con 7 documenti misura solo l'**overhead fisso** del sistema, non la scalabilità. Per quella si usa l'indice completo (4.3).

### 4.2 Copertura (indice completo) ✅

| Verifica | Valore |
|---|---|
| Righe nel dump | 12.261 |
| Articoli importati / scartati per errore | 12.261 / **0** |
| Documenti nell'indice | 12.213 (= id distinti) |
| Differenza | 48 duplicati: `updateDocument` tiene la copia più recente |
| Articoli senza abstract / senza categorie | 266 / 23, indicizzati senza errori |

### 4.3 Efficienza

#### Codec: `SimpleTextCodec` (usato oggi) contro codec di default

Stesso indice (12.213 documenti) costruito con i due codec; latenza su 28 query reali (`queries.tsv`) × 20 giri dopo 5 di riscaldamento.

| Codec | Indicizzazione | Articoli/s | Dimensione indice | Latenza media | Mediana | p95 | Ricerche/s |
|---|---|---|---|---|---|---|---|
| `SimpleTextCodec` | 33,4 s | 367 | **342,8 MB** | 12,14 ms | 10,14 ms | 26,95 ms | 82 |
| Codec di default | 28,3 s | 433 | **37,5 MB** | 5,71 ms | 5,13 ms | 10,82 ms | 175 |

Confronto con le misure del collega su Apple M1 (due esecuzioni): `SimpleText` 17,0–17,4 s, 703–720 art/s, media 6,5–7,2 ms, p95 15–18 ms;
default 12,3–12,6 s, 975–996 art/s, media 2,4–3,8 ms, p95 4–10 ms; stessi 342,8 e 37,5 MB.

**Cosa vuol dire:**
- **Dimensione (deterministica ✅): 9,1 volte più piccola** (37,5 contro 342,8 MB). Rispetto al dump da 564 MB: 6,6% contro 60,8%.
  Il formato di default è binario e compresso; quello testuale no. (L'indice è già molto più piccolo del dump perché teniamo il testo estratto, non l'HTML.)
- **Ricerca: circa 2 volte più veloce** (mediana 5,1 contro 10,1 ms; p95 10,8 contro 27,0 ms): stesso rapporto sulle due macchine.
- **Indicizzazione: 1,2–1,4 volte più veloce**: meno byte da scrivere.
- I risultati delle ricerche sono identici: cambia solo il costo.
- **Conclusione:** `SimpleTextCodec` serve per il debug (lo abbiamo usato per vedere la struttura dell'indice, come a lezione), non per l'uso.
  Raccomandazione: **toglierlo da `LuceneManager`**. Anche così, con il codec testuale la ricerca resta usabile (p95 sotto i 30 ms).

#### Latenza per tipo di query (`RealIndexPerformanceTest`, indice completo, 21 query × 10 esecuzioni dopo la prima)

Queste 21 query sono volutamente "difficili" (wildcard, `+`/`-`, filtri combinati): è un test di stress, più pesante del traffico tipico.

| Query | Hit | Prima (ms) | Min | Media | Max |
|---|---|---|---|---|---|
| termine singolo (`scuola`) | 364 | **5788,05** | 11,26 | 17,81 | 37,84 |
| termine frequente (`europa`) | 8643 | 38,27 | 18,33 | 30,76 | 40,63 |
| altro termine (`governo`) | 1145 | 25,21 | 18,67 | 22,75 | 29,71 |
| nome proprio (`Roma`) | 2656 | 26,02 | 17,50 | 23,91 | 35,69 |
| due termini | 100 | 40,56 | 13,81 | 21,07 | 33,92 |
| frase esatta | 1 | 41,57 | 25,70 | 35,84 | 44,38 |
| `+calcio -serie` | 1031 | 70,05 | 50,91 | 65,31 | 76,29 |
| campo `categories:sport` | 4091 | 18,27 | 6,49 | 11,74 | 14,87 |
| 2 parole (default) | 39 | 26,46 | 10,49 | 17,61 | 28,01 |
| 2 parole con OR | 485 | 55,92 | 15,43 | 20,09 | 37,54 |
| 2 parole con AND | 39 | 28,79 | 11,05 | 16,24 | 25,23 |
| wildcard (`terrem*`) | 166 | 96,05 | 10,32 | 42,37 | 79,41 |
| query lunga | 1 | 29,84 | 24,98 | 31,04 | 51,60 |
| nessun risultato | 0 | **2222,56** | 13,73 | 15,17 | 17,07 |
| testo + categoria | 778 | 32,94 | 21,08 | 31,37 | 61,78 |
| solo categoria | 4091 | 24,51 | 15,93 | 17,11 | 19,03 |
| testo + date | 456 | 103,17 | 44,95 | 53,01 | 79,79 |
| solo date | 6306 | 17,44 | 12,20 | 13,94 | 18,13 |
| testo + categoria + date | 207 | 45,45 | 24,36 | 36,55 | 42,35 |
| ordinata per data | 695 | 15,25 | 13,06 | 14,20 | 17,54 |
| tutti i documenti | 12213 | 8,47 | 4,38 | 6,55 | 10,85 |

Riepilogo: 210 ricerche, **media 25,9 ms**, la più lenta 79,8 ms, 39 ricerche/s su un thread.

**Cosa vuol dire:**
- **Il default è AND (conferma sperimentale):** "2 parole" senza operatore dà 39 risultati, come l'AND esplicito; con OR esplicito sono 485.
- **Query più pesanti:** `+`/`-` (65 ms), testo + date (53 ms), wildcard (42 ms; si espande in molti termini) e frase (36 ms; confronta le posizioni delle parole): più clausole o più posizioni da valutare.
- **Query leggere:** "tutti i documenti" (6,6 ms), query per data (14 ms) e per categoria (17 ms).
- **La prima esecuzione è molto più lenta** e va riportata a parte: ~5,8 s per la primissima query (avvio JVM, caricamento classi, apertura dei file dell'indice) e ~2,2 s per la prima query senza risultati
  (inizializzazione del correttore dei suggerimenti). Dopo, 17,8 e 15,2 ms. Sviluppo futuro: **riscaldare cache e suggerimenti all'avvio**.
- **Perché il valore medio qui (25,9 ms) è più alto di quello della tabella dei codec (12,1 ms)?** Sono due carichi diversi: qui query pesanti e una combinazione di filtri
  (stress), là le 28 query realistiche di `queries.tsv`. Inoltre i test sono stati eseguiti tutti nella stessa sessione: per i valori finali conviene rilanciarli da soli, due volte (sezione 11).
- Questo test usa l'indice dell'applicazione, cioè **con `SimpleTextCodec`**: dopo averlo tolto le latenze scenderanno (≈ metà).

### 4.4 Efficacia: known-item (automatica) ✅

500 articoli a caso (seme fisso 42, quindi ripetibile) cercano se stessi; si guardano i primi 100 risultati.
Configurazione: AND, titolo 3, abstract 2, contenuto 1, categorie 1, prossimità 2 (slop 3).

| Query | N | MRR | Success@1 | Success@10 | Non trovati |
|---|---|---|---|---|---|
| Titolo | 500 | **0,959** | 0,938 | 0,996 | 0 |
| 4 parole del testo (non presenti nel titolo) | 429 | **0,830** | 0,795 | 0,893 | 20 |

- Le due varianti servono a non farsi ingannare: cercando col titolo si favorisce il boost del titolo; cercando con parole del testo no.
- 71 articoli non hanno abbastanza parole adatte nell'abstract, perciò le query dal testo sono 429.
- **Perché 20 non trovati:** quasi tutti sono cronache sportive con lo stesso schema ("Calcio, Serie A 2011-2012: 35ª giornata"): le parole scelte
  ("classifica", "risultati", "giornata") compaiono in centinaia di articoli quasi identici, quindi la query è ambigua anche per una persona. È un limite del metodo più che del motore.

### 4.5 Efficacia: valutazione per argomento con giudizi di rilevanza ✅

- **10 query** scelte per le domande aperte dell'ablazione: concetti di più parole (q01 terremoto Abruzzo, q23 incidente ferroviario, q25 sciopero trasporti),
  query dove AND trova pochi articoli (q04, q13, q20), argomenti ampi (q02 terremoto, q10 Palio di Siena, q27 referendum), una persona (q08 Papa Francesco).
- Ogni query ha un **bisogno informativo** (`need`): si giudica rispetto al bisogno, non alle parole.
- **Pool:** primi 10 risultati di ognuna delle 5 configurazioni, uniti → **135 coppie** query–articolo.
- **Voti:** 0 = non risponde, 1 = in parte, 2 = pienamente (99 × 2, 22 × 1, 14 × 0).
- **Chi ha giudicato:** il modello Claude Opus 5.5, il 19/11/2026, leggendo bisogno, titolo e inizio dell'articolo. **Controllo umano su un campione: da fare** (vedi checklist).

| Configurazione | P@10 | Recall@100 | MAP | MRR | nDCG@10 |
|---|---|---|---|---|---|
| **Attuale** (AND, boost 3/2/1/1, prossimità, categorie) | 0,920 | 0,977 | 0,920 | 1,000 | 0,919 |
| OR invece di AND | 0,920 | **1,000** | 0,923 | 1,000 | **0,929** |
| Senza prossimità | 0,910 | 0,977 | 0,924 | 1,000 | 0,887 |
| Boost tutti a 1 | **0,940** | 0,971 | 0,913 | 1,000 | 0,918 |
| Senza categorie nella ricerca | 0,920 | 0,977 | 0,902 | 1,000 | 0,918 |

**Come leggerla:**
- **MRR = 1 ovunque:** per tutte le 10 query il primo risultato è rilevante in ogni configurazione; il sistema non sbaglia la "risposta principale".
- **Prossimità: utile.** Togliendola nDCG@10 scende da 0,919 a 0,887; l'effetto è concentrato dove le parole formano un concetto:
  q10 "Palio di Siena" 0,709 → 0,477, q23 "incidente ferroviario" 1,000 → 0,887. Il known-item non lo vedeva (parole scelte a caso, non vicine): **metodi diversi misurano cose diverse**.
- **AND contro OR: il costo di AND esiste ma è localizzato.** Su 9 query su 10 i risultati sono identici; su q20 "guerra Ucraina Russia" AND perde 3 rilevanti su 13
  (Recall@100 0,769 contro 1,000), perché quegli articoli non contengono tutte e tre le parole. Sviluppo futuro: *minimum should match* (almeno 2 parole su 3).
- **Boost e categorie:** differenze di pochi centesimi, con 10 query non significative.

### 4.6 Efficacia: ablazione con known-item ✅

| Configurazione | MRR titolo | S@1 titolo | MRR testo | S@1 testo | S@10 testo |
|---|---|---|---|---|---|
| Completa (attuale) | **0,959** | **0,938** | 0,830 | 0,795 | 0,893 |
| Titolo boost 1 invece di 3 | 0,920 | 0,880 | **0,847** | **0,818** | 0,895 |
| Tutti i boost a 1 | 0,943 | 0,916 | 0,834 | 0,802 | 0,893 |
| **OR invece di AND** | 0,924 | 0,906 | **0,585** | 0,480 | 0,786 |
| Senza prossimità | 0,957 | 0,934 | 0,830 | 0,795 | 0,893 |
| Senza categorie nella ricerca | 0,959 | 0,936 | 0,827 | 0,790 | 0,890 |

- **Effetto netto solo dall'operatore:** con OR l'MRR sulle query dal testo crolla da 0,830 a 0,585, perché articoli che contengono *una sola* parola ma nel titolo superano quello cercato.
- **Boost del titolo:** compromesso atteso (+0,04 sul titolo, −0,02 sul testo). Conta il **rapporto** tra i boost: "titolo 1, abstract 2" è peggio di "tutti a 1" sulle query dal titolo (0,920 contro 0,943).
- **Cautela:** il known-item favorisce AND per costruzione (l'articolo cercato contiene sempre *tutte* le parole della query), quindi non può mostrare il costo di AND; per quello serve 4.5 (q20).

---

## 5. Cosa dimostrano i numeri: scelta → evidenza → decisione

| Scelta | Evidenza | Decisione |
|---|---|---|
| **AND di default** | known-item: OR perde molto (0,830 → 0,585 sul testo); giudizi: OR recupera 3 rilevanti in più solo su q20 | **tenuto**; con *minimum should match* come sviluppo futuro |
| **Prossimità** | giudizi: nDCG 0,919 contro 0,887 senza; known-item: nessun effetto | **tenuta**: serve per i concetti di più parole |
| **Boost titolo 3** | +0,04 MRR dal titolo, −0,02 dal testo | **tenuto** (compromesso dichiarato) |
| **Categorie nella ricerca libera** | MAP 0,920 contro 0,902 senza; known-item neutro | **tenute** (lievemente utili) |
| **Analyzer dedicato per le categorie con stop word** ⚠️ | `categories:pubblicati` trova 0 articoli con l'analyzer attuale, 210 senza le stop word personalizzate | **tenuto**: elimina il rumore delle categorie tecniche |
| **Codec** | indice 9× più piccolo, ricerca ≈ 2× più veloce, indicizzazione 1,2–1,4× più veloce col default | **da cambiare**: togliere `SimpleTextCodec` |
| **ItalianAnalyzer / stemming** ⚠️ | vedi 6.1: più recall (`scuola` 6 contro 4 risultati) | tenuto |
| **BM25** ⚠️ | vedi 6.1: TF-IDF molto peggio sulle query dal testo | tenuto |

---

## 6. Ablazioni su modello di ranking e analyzer ⚠️

I test `ablationSimilarity`, `ablationBm25` e `ablationAnalyzer` (26/Sezione 11) vanno **rieseguiti sull'indice completo** prima di citare i numeri: l'esecuzione
che ha prodotto i dati seguenti ha usato un indice di prova di soli **250 articoli** (il test 01 stampava `Documenti nell'indice: 250`),
e con indici così piccoli quasi tutto sta vicino al massimo (MRR 0,95–0,99), quindi le differenze piccole non significano nulla.
Le differenze **grandi** sono comunque indicative e probabilmente si ripeteranno.

Metodo di questi test (diverso da 4.4, **non mischiare le tabelle**): 200 articoli a caso, query = titolo o prime 8 parole dell'abstract, primi 10 risultati.
La "baseline" di questa esecuzione usava OR e niente prossimità, quindi non corrispondeva al sistema attuale (AND + prossimità): nella versione finale va impostata AND.

### 6.1 Risultati preliminari (250 articoli)

| Variante | MRR titolo | MRR abstract |
|---|---|---|
| BM25 k1=1,2 b=0,75 (default Lucene) | 0,988 | 0,961 |
| TF-IDF (`ClassicSimilarity`) | 0,991 | **0,754** |
| Modello a linguaggio Jelinek-Mercer 0,1 | 0,991 | 0,963 |
| Modello a linguaggio Dirichlet | **0,942** | 0,953 |

| Parametri BM25 | Intervallo MRR titolo | Intervallo MRR abstract |
|---|---|---|
| k1 ∈ {0,5; 1,2; 2,0} × b ∈ {0; 0,75; 1} | 0,981 – 0,993 | 0,944 – 0,961 |

| Analyzer | MRR titolo | MRR abstract | Risultati "europa" / "scuola" |
|---|---|---|---|
| ItalianAnalyzer (attuale) | 0,988 | 0,961 | 170 / 6 |
| StandardAnalyzer (niente stemming né stop word) | 0,991 | 0,968 | 169 / **4** |

Effetto delle stop word sulle categorie: `categories:pubblicati` trova **0** articoli con l'analyzer dedicato e **210** senza.

**Cosa vuol dire:**
- **BM25 contro TF-IDF:** la differenza è grande sulle query dal testo (0,961 contro 0,754): TF-IDF non normalizza bene la lunghezza, e le query dal testo trovano articoli lunghi.
  Sul titolo sono pari.
- **Parametri BM25:** quasi indifferenti (variazione < 0,02): i valori di default di Lucene sono ragionevoli, non vale la pena tararli.
- **Stemming:** non cambia l'MRR (anzi, sul piccolo indice è lievemente peggiore), ma **trova più articoli** (6 contro 4 per "scuola"): il suo contributo è la recall, che il known-item non misura.
- **Stop word delle categorie:** elimina completamente il rumore delle categorie tecniche ("Pubblicati").

---

## 7. Invarianti (test metamorfici) ⚠️ (i valori vengono dall'indice da 250 articoli; le relazioni valgono in generale)

Ogni test verifica una relazione che deve essere sempre vera; è un modo di testare senza conoscere la risposta "giusta".

| Invariante | Esito |
|---|---|
| stessa query = stessi risultati nello stesso ordine | ✅ |
| maiuscole/minuscole irrilevanti (`europa`, `EUROPA`, `Europa`) | ✅ stesso numero di risultati |
| `totalHits` non dipende da pagina né da dimensione di pagina | ✅ |
| aggiungere un filtro (categoria, data) non aumenta i risultati; il primo risultato resta primo | ✅ |
| intervalli di date adiacenti non si sovrappongono (fino al giorno prima + dal giorno = totale) | ✅ 150 + 20 = 170 |
| inclusione–esclusione: \|A ∪ B\| = \|A\| + \|B\| − \|A ∩ B\| | ✅ 170 + 6 − 4 = 172 |
| \|A senza B\| = \|A\| − \|A ∩ B\|, e viceversa | ✅ 166 e 2 |
| una frase "A B" implica A e B | ✅ |
| le pagine 1–5 da 10 risultati = la pagina 1 da 50, senza duplicati | ✅ 50 risultati |

---

## 8. Limiti da dichiarare apertamente

1. **I giudizi di rilevanza sono di un LLM** (Claude Opus 5.5), che ha letto solo titolo e inizio dell'articolo, e lo stesso assistente ha contribuito a sviluppare il sistema.
   Per questo serve il controllo umano su un campione e va indicato l'accordo.
2. **Poche query** (10) e pool profondo 10: molte query sono al "soffitto" (P@10 = 1 per 7 query su 10); le differenze di pochi centesimi non sono significative.
3. **Recall relativa ai rilevanti noti nel pool**, non a tutti quelli del dump.
4. **Known-item:** favorisce AND e (nella variante dal titolo) il boost del titolo; non misura la recall per argomento.
5. **Le misure di tempo dipendono dalla macchina e dal carico**: riportare intervalli, mediane e il p95, escludere il riscaldamento.
6. **`SimpleTextCodec` ancora attivo** (finché non viene tolto): le latenze dell'applicazione sono circa il doppio del possibile.
7. **Prima ricerca lenta** (secondi) dopo l'avvio.
8. **Il filtro categoria è una frase sul campo analizzato**: "Italia" trova anche "Calcio in Italia". Servirebbe un campo con una categoria per valore (reindicizzazione).
9. Ablazioni su analyzer e similarity ancora da confermare sull'indice completo (sezione 6).

### Query da analizzare a mano (successi e fallimenti)

- **Successo, q10 "Palio di Siena":** senza prossimità nDCG 0,709 → 0,477. Le parole vicine formano un concetto e premiare la vicinanza porta in alto gli articoli giusti.
- **Successo, q23 "incidente ferroviario":** 1,000 → 0,887 senza prossimità.
- **Fallimento di AND, q20 "guerra Ucraina Russia":** 3 rilevanti su 13 non trovati (non contengono tutte e tre le parole). Rimedio: *minimum should match*.
- **Ambiguità, q13 "Mostra del cinema di Venezia":** solo 5 rilevanti trovati; molti articoli usano "arte cinematografica", non "cinema", e "mostra"/"Venezia" portano anche mostre d'arte e uno sciopero dello spettacolo.
- **Fallimento del known-item:** cronache sportive con lo stesso schema ("Calcio, Serie A 2011-2012: 35ª giornata"): query ambigua.

---

## 9. Scaletta della presentazione (20 minuti)

| Min | Slide | Contenuto |
|---|---|---|
| 0–1 | Titolo | cosa abbiamo costruito |
| 1–4 | **Sfide** | dati sporchi (HTML, 48 duplicati, 266 senza abstract), lingua italiana, nessun benchmark disponibile, come misurare |
| 4–9 | **Soluzioni** | pipeline (schema 1.2); campi e analyzer; query (boost, AND, prossimità, filtri); snippet e suggerimenti; screenshot dell'interfaccia (senza codice) |
| 9–12 | **Strategia di test** | i quattro livelli (tabella di sezione 3); perché indice di prova *e* indice reale; cos'è un'ablazione |
| 12–18 | **Risultati** | (1) codec: 9× più piccolo, ≈2× più veloce; (2) latenza e p95; (3) known-item: MRR 0,959 / 0,830; (4) giudizi: tabella 4.5; (5) AND contro OR e prossimità con gli esempi q20, q10; (6) similarity e analyzer |
| 18–20 | **Limiti e sviluppi futuri** | giudici LLM, poche query, minimum should match, riscaldamento all'avvio, campo categoria esatto |
| demo | | query frase, `+`/`-`, filtri, ordinamento per data, un refuso ("Forse cercavi…"), autocompletamento categorie |

Consiglio: ogni slide di risultati deve avere **una frase di conclusione** ("quindi abbiamo tenuto X perché Y") — è ciò che l'homework chiede ("come i risultati supportano le scelte").

---

## 10. Domande probabili e risposte

- **Perché Lucene e non un database?** Indice invertito, ranking BM25, analisi del testo italiano e query booleane/frase già pronti; un DB relazionale non ordina per rilevanza.
- **Cos'è BM25 e perché non TF-IDF?** Come TF-IDF ma con saturazione delle frequenze e normalizzazione della lunghezza; nei nostri test TF-IDF è nettamente peggiore sulle query dal testo (6.1).
- **Perché AND di default?** Precisione; abbiamo misurato il costo (q20) e il beneficio (known-item).
- **Perché non usare solo il known-item?** Misura solo il ritrovare un articolo e favorisce AND; per questo abbiamo aggiunto giudizi per argomento.
- **I giudizi sono affidabili?** Sono di un LLM, con i limiti dichiarati; su un campione di 20 li abbiamo verificati a mano (accordo X%).
- **Perché il p95 e non solo la media?** I picchi (GC) falsano la media; il p95 dice cosa vive tipicamente l'utente.
- **Cos'è il codec e perché cambia le prestazioni?** Formato di scrittura dell'indice: quello testuale è leggibile ma non compresso né ottimizzato.
- **Come scalerebbe?** Su un solo nodo 12.213 articoli rispondono in pochi ms; per milioni di documenti si userebbero più shard (Elasticsearch, costruito su Lucene).
- **Ricerca semantica?** Possibile con vettori (`KnnVectorField` / HNSW in Lucene): sviluppo futuro.
- **Come gestite i refusi?** Suggerimento "Forse cercavi…" quando i risultati sono 0 (distanza di edit / n-grammi sul vocabolario).
- **Perché la prima ricerca è lenta?** Avvio JVM, apertura file, inizializzazione del correttore: si paga una volta; sviluppo futuro: riscaldamento all'avvio.

---

## 11. Come riprodurre (da `wikinews/`)

```bash
./mvnw test                                                  # 13 test funzionali su indice in memoria (+ test di metriche)
./mvnw test -Dtest=EfficiencyEvaluationTest -DexcludedGroups=none     # codec: tempo, dimensione, latenza (circa 1-2 minuti)
./mvnw test -Dtest=RealIndexPerformanceTest -DexcludedGroups=none     # latenza per tipo di query
./mvnw test -Dtest=KnownItemEvaluationTest  -DexcludedGroups=none     # known-item
./mvnw test -Dtest=PoolBuilderTest          -DexcludedGroups=none     # genera il pool da giudicare
./mvnw test -Dtest=RetrievalEvaluationTest  -DexcludedGroups=none     # P@10, Recall@100, MAP, MRR, nDCG@10
```

Per le tabelle dei tempi: lanciare Efficienza e Latenza **da soli** (non nella stessa sessione degli altri test), due volte, e riportare l'intervallo o la mediana.

Messaggi nel log che si possono ignorare: avvisi su `jdk.incubator.vector`, `Linker`/accesso nativo, auto-attach di Mockito, e
"Totale articoli importati: 2" all'avvio dei test (indice minimo del contesto di test, non l'indice di valutazione).

---

## 12. Checklist prima della consegna

- [ ] Rieseguire similarity, BM25 e analyzer sull'indice completo e sostituire la sezione 6
- [ ] Controllo umano di 20 giudizi (2 per query): contare gli accordi e scriverli in 4.5
- [ ] Togliere `SimpleTextCodec` da `LuceneManager`; rilanciare `RealIndexPerformanceTest`; aggiornare la tabella delle latenze nel `README.MD` (oggi viene da 100 articoli)
- [ ] Completare l'hardware in testa a questo file
- [ ] Una sola tabella finale di ablazione nella relazione (known-item + giudizi + similarity/analyzer), con la fonte di ogni colonna
- [ ] Slide secondo la scaletta (sezione 9) e prova della demo