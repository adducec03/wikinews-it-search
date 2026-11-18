# Note sui test e sulla valutazione

Appunti del gruppo per la parte "strategia di test" dell'homework e per la relazione finale:
cosa misuriamo, come leggere le metriche, quali scelte verificare con l'ablation e quali errori evitare.
Lo stato dei lavori (fatto / da fare) è in `README_LONG.md`.

Ultimo aggiornamento: 2026-11-18

---

## 1. Strategia in breve

La valutazione è su quattro livelli, dal più piccolo al più completo:

| Livello | Domanda | Dove |
|---|---|---|
| **Correttezza** | Ogni componente fa quello che deve? | `WikinewsApplicationTests` (indice in memoria, 7 articoli costruiti a mano) |
| **Copertura** | L'indicizzazione perde articoli? | log di `ImportService` (test automatico ancora da fare) |
| **Efficienza** | Quanto è veloce e quanto occupa? | `WikinewsApplicationTests` test 13, `RealIndexPerformanceTest` |
| **Efficacia** | I risultati sono pertinenti? | `KnownItemEvaluationTest` (automatico), pool + giudizi manuali, categorie (da fare) |

Il punto più importante per un corso di IR è l'**efficacia**: tutto il resto dimostra che il sistema funziona,
l'efficacia dimostra che funziona *bene* e permette di giustificare le scelte con numeri.

---

## 2. Inventario dei test

| Classe | Tag | Indice | Cosa verifica | Comando (da `wikinews/`) |
|---|---|---|---|---|
| `WikinewsApplicationTests` | — | memoria, 7 articoli | analyzer, boost, sintassi, filtri, ordinamento, paginazione, snippet, casi limite, latenza | `./mvnw test` |
| `IrMetricsTest` | — | nessuno | le formule delle metriche su esempi calcolati a mano | `./mvnw test` |
| `RealIndexPerformanceTest` | — (da aggiungere `evaluation`) | quello dell'app | latenza di 21 query tipiche | `./mvnw test -Dtest=RealIndexPerformanceTest` |
| `PoolBuilderTest` | `evaluation` | completo | genera il pool da giudicare a mano | `./mvnw test -Dtest=PoolBuilderTest -DexcludedGroups=none` |
| `KnownItemEvaluationTest` | `evaluation` | completo | known-item: MRR, Success@1, Success@10 | `./mvnw test -Dtest=KnownItemEvaluationTest -DexcludedGroups=none` |

I test con tag `evaluation` sono esclusi dal normale `./mvnw test` (configurato nel `pom.xml`) perché
richiedono il dump e indicizzano tutti i 12.261 articoli in `target/evaluation-index` (circa 30 s).

---

## 3. Le metriche

### Efficacia (qualità dei risultati)

Notazione: per una query il motore restituisce una lista ordinata (ranking); "rilevanti" sono gli articoli
giudicati pertinenti. Le formule sono in `IrMetrics` e verificate in `IrMetricsTest`.

| Metrica | Formula, a parole | Cosa racconta | Quando è la più adatta |
|---|---|---|---|
| **Precision@K** | rilevanti nei primi K / K | quanto è "pulita" la prima pagina | l'utente guarda solo i primi 10 (la UI ne mostra 10) |
| **Recall@K** | rilevanti nei primi K / totale rilevanti | quanto di ciò che esiste viene mostrato | query su un argomento con molti articoli |
| **RR / MRR** | 1 / posizione del primo rilevante; media su tutte le query | quanto presto trovo *una* risposta giusta | query con una sola risposta (known-item) |
| **AP / MAP** | media delle precisioni nei punti in cui compare un rilevante; media su tutte le query | qualità di tutta la classifica: ordine e completezza | metrica riassuntiva standard |
| **nDCG@K** | guadagno (2^voto − 1) scontato per log2(posizione + 1), diviso il valore ideale | come MAP ma con voti 0/1/2 | giudizi graduati |
| **Success@K** | 1 se il rilevante è nei primi K, altrimenti 0; media | "l'ho trovato in prima pagina?" | known-item (con un solo rilevante coincide con Recall@K) |

Da ricordare quando si commentano i numeri:
- **P@10 si divide sempre per 10**: se una query ha solo 3 articoli rilevanti in tutto il dump, P@10 non può superare 0,3.
  Per query così specifiche conviene guardare MRR o R-precision.
- **AP divide per il totale dei rilevanti**: un rilevante mai trovato abbassa il punteggio. Premia sia l'ordine sia la completezza.
- **MRR guarda solo il primo rilevante**: non distingue un sistema che ne trova 1 da uno che ne trova 10.
- **Le medie nascondono le query difficili**: riportare sempre anche 2–3 query andate male e spiegarne il motivo.

### Efficienza

| Metrica | Note |
|---|---|
| Tempo di indicizzazione, articoli al secondo | da misurare a fine `ImportService` |
| Dimensione dell'indice su disco | confrontarla con il dump (590 MB) |
| Latenza: media, mediana, **p95**, max | p95 = "il 95% delle ricerche risponde entro X ms": rappresenta l'esperienza dell'utente meglio della media. Escludere le prime esecuzioni (riscaldamento della JVM) |
| Throughput | ricerche al secondo su un thread |

### Copertura

| Verifica | Valore atteso |
|---|---|
| Righe del dump | 12.261 |
| Articoli indicizzati | 12.261, errori 0 |
| Documenti nell'indice | 12.213 (= id distinti; 48 duplicati sostituiti dalla versione più recente) |
| Articoli senza abstract / senza categorie | 266 / 23, indicizzati senza errori |

---

## 4. Da dove vengono i giudizi di rilevanza

| Metodo | Come | Pro | Limiti |
|---|---|---|---|
| **Known-item** (fatto) | un articolo cerca se stesso; query dal titolo o da 4 parole del testo non presenti nel titolo | automatico, centinaia di query, ripetibile (seme fisso) | misura il *ritrovare un articolo*, non il *trovare tutto su un argomento*. Le query dal titolo favoriscono il boost del titolo: per questo c'è anche la variante dal testo |
| **Categorie** (da fare) | query = nome di una categoria; rilevanti = articoli con quella categoria | automatico, giudizi per argomento | **va escluso il campo `categories` dalla ricerca**, altrimenti il sistema trova la categoria "barando". Le categorie sono incomplete: la precisione viene sottostimata. Buono per *confrontare* configurazioni |
| **Giudizi manuali** (pool pronto) | 28 query con bisogno informativo (`need`), primi 20 risultati, voto 0/1/2 | è il riferimento "vero" | costoso (530 giudizi). Si può ridurre a ~10 query × 10 risultati per **validare** i metodi automatici |

Regole per i giudizi manuali:
- Si giudica rispetto al **`need`** in `queries.tsv`, non alle parole della query.
- **0** = non risponde al bisogno, **1** = in parte, **2** = risponde pienamente.
- Giudicare senza guardare posizione e punteggio, per non farsi influenzare dal motore.
- Se possibile, far giudicare alcune query da due persone e misurare quanto sono d'accordo (accordo tra giudici).
- Il pool viene da **una sola configurazione**: un articolo rilevante che il motore non mette tra i primi 20
  non viene mai giudicato. Per l'ablation conviene allargare il pool con i risultati delle varianti (pooling vero).

---

## 5. Ablation study

### Metodo
1. Si parte dalla **configurazione completa** (quella attuale).
2. Si cambia **una sola scelta alla volta**, lasciando tutto il resto uguale.
3. Si misurano **le stesse query** (stesso seme nel known-item, stesse query giudicate) e le stesse metriche.
4. Una scelta è utile se toglierla **peggiora** le metriche; se non cambia nulla, è una complicazione inutile.
5. Con poche query il rischio di overfitting è alto: scegliere i parametri su metà delle query e
   riportare i risultati sull'altra metà.

**Prerequisito:** oggi boost, operatore e prossimità sono costanti in `QueryFactory`. Vanno resi
configurabili (per esempio una classe `SearchSettings`) per provare le varianti in un'unica esecuzione.

### Scelte da verificare

| Scelta attuale | Varianti | Reindicizzare? | Cosa ci aspettiamo / perché è interessante |
|---|---|---|---|
| Boost dei campi titolo 3, abstract 2, contenuto 1, categorie 1 | tutti a 1; titolo 5; senza abstract | no | il boost del titolo aiuta il known-item da titolo; controllare che non peggiori le query per argomento |
| Operatore AND | OR | no | AND aumenta la precisione ma riduce i risultati. Esempio: q13 "Mostra del cinema di Venezia" trova solo 8 articoli con AND |
| Boost di prossimità (slop 3, boost 2) | disattivato; slop 0; slop 10 | no | dovrebbe aiutare le query di più parole che formano un concetto ("incidente ferroviario") |
| Categorie nella ricerca libera | escluse | no | possono aiutare (query = argomento) o introdurre rumore (categorie tecniche come "Pubblicati") |
| A parità di punteggio, il più recente prima | solo punteggio | no | effetto atteso piccolo: i pareggi esatti sono rari |
| BM25 (k1 = 1,2, b = 0,75) | TF-IDF (`ClassicSimilarity`); BM25 con b diversi | no (`searcher.setSimilarity`) | confronto classico della teoria; b regola quanto pesa la lunghezza del documento |
| `ItalianAnalyzer` (stemming, stop word, elisioni) | `StandardAnalyzer` | **sì** | lo stemming dovrebbe aumentare la recall ("elezione" trova "elezioni") |
| Stop word aggiuntive sulle categorie ("pubblicati", "articoli") | senza | sì | effetto sulle query che includono le categorie |
| `SimpleTextCodec` | codec di default | sì | solo efficienza: tempo di indicizzazione, dimensione, latenza |

### Tabella da riempire (una per metodo di valutazione)

| Configurazione | MRR (titolo) | MRR (testo) | Success@10 (testo) | P@10 | MAP | nDCG@10 | Latenza media |
|---|---|---|---|---|---|---|---|
| Completa (attuale) | 0,959 | 0,830 | 0,893 | | | | |
| − boost dei campi | | | | | | | |
| OR invece di AND | | | | | | | |
| − prossimità | | | | | | | |
| − categorie nella ricerca | | | | | | | |
| TF-IDF invece di BM25 | | | | | | | |
| `StandardAnalyzer` | | | | | | | |

---

## 6. Risultati raccolti finora

### Known-item (configurazione attuale, 500 articoli, seme 42, primi 100 risultati)

| Query | N. query | MRR | Success@1 | Success@10 | Non trovati |
|---|---|---|---|---|---|
| Titolo | 500 | 0,959 | 0,938 | 0,996 | 0 |
| 4 parole del testo | 429 | 0,830 | 0,795 | 0,893 | 20 |

Osservazioni:
- 71 articoli hanno un abstract troppo corto per la variante dal testo.
- Quasi tutti i non trovati sono cronache sportive con lo stesso schema ("Calcio, Serie A 2011-2012: 35ª giornata"):
  le parole scelte compaiono in centinaia di articoli quasi identici. Limite del metodo più che del motore.

---

## 7. Attenzione (errori facili da fare)

- **Indice ridotto:** `RealIndexPerformanceTest` è un `@SpringBootTest` e usa `wikinews.data.maxArticle=99`,
  quindi misura un indice di 100 articoli. Anche la tabella delle latenze nel `README.MD` viene da lì.
  Per numeri validi va avviato con l'indice completo.
- **Latenze e `SimpleTextCodec`:** l'indice è in formato testuale (solo per debug); le latenze misurate
  oggi sono peggiori di quelle con il codec di default. Dichiararlo o rimisurare dopo il cambio.
- **Riscaldamento della JVM:** la prima query è molto più lenta delle successive (compilazione JIT, cache).
  Escluderla dalle medie, come fa già il test 13.
- **Circolarità delle categorie:** se si valuta con le categorie, il campo `categories` non deve essere cercato.
- **Bias del titolo nel known-item:** riportare sempre entrambe le varianti (titolo e testo).
- **Confronti equi:** stesse query, stesso seme, stesso indice per tutte le configurazioni.
- **Pool rigenerato = giudizi persi:** il pool va rigenerato solo se cambiano le query.
- **4 test in errore:** in `WikinewsApplicationTests` `SearchService` è creato con `null` al posto di
  `QuerySuggestionService`; `luceneSyntax`, `sorting`, `errorsAndEdgeCases` e `searcherRefresh` falliscono.
  Va sistemato prima della consegna.

---

## 8. Checklist per la relazione finale

- [ ] Descrizione della strategia di test (i quattro livelli della sezione 1)
- [ ] Tabella di copertura dell'indicizzazione
- [ ] Efficienza: tempo di indicizzazione, dimensione dell'indice, latenza (media, p95) sull'indice completo
- [ ] Efficacia: known-item (titolo e testo), categorie, giudizi manuali
- [ ] Spiegazione delle metriche usate e perché
- [ ] Tabella dell'ablation con il commento di ogni scelta (tenuta, cambiata o tolta)
- [ ] 2–3 query analizzate a mano (successi e fallimenti, con il motivo)
- [ ] Limiti della valutazione (sezione 7) dichiarati apertamente
