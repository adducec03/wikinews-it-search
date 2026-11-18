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
| `QueryFactorySettingsTest` | — | nessuno | i pesi di `SearchSettings` finiscono davvero nella query (boost, campo escluso, prossimità) | `./mvnw test` |
| `RealIndexPerformanceTest` | — (da aggiungere `evaluation`) | quello dell'app | latenza di 21 query tipiche | `./mvnw test -Dtest=RealIndexPerformanceTest` |
| `PoolBuilderTest` | `evaluation` | completo | genera il pool da giudicare: 10 query, primi 10 di ogni configurazione, uniti | `./mvnw test -Dtest=PoolBuilderTest -DexcludedGroups=none` |
| `RetrievalEvaluationTest` | `evaluation` | completo | P@10, Recall@100, MAP, MRR, nDCG@10 per ogni configurazione, con i giudizi di `qrels.tsv` (saltato se il file manca) | `./mvnw test -Dtest=RetrievalEvaluationTest -DexcludedGroups=none` |
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
| **Giudizi manuali** (pool pronto) | 10 delle 28 query, con bisogno informativo (`need`); primi 10 risultati di ognuna delle 5 configurazioni, uniti; voto 0/1/2 | è il riferimento "vero"; misura anche la Recall, che il known-item non vede | 135 giudizi a mano |

Regole per i giudizi manuali:
- Si giudica rispetto al **`need`** in `queries.tsv`, non alle parole della query.
- **0** = non risponde al bisogno, **1** = in parte, **2** = risponde pienamente.
- Giudicare senza guardare posizione e punteggio, per non farsi influenzare dal motore.
- Se possibile, far giudicare alcune query da due persone e misurare quanto sono d'accordo (accordo tra giudici).
- Il pool unisce i primi 10 risultati di **tutte** le configurazioni confrontate (`AblationConfigurations`):
  così un articolo trovato solo con OR, o solo senza boost, viene comunque giudicato e il confronto è equo.
  Gli articoli mai giudicati contano come non rilevanti (regola standard del pooling): la Recall è quindi
  relativa ai rilevanti *conosciuti*, non a tutti quelli del dump.
- Le righe del pool sono ordinate per titolo, non per posizione, per non influenzare chi giudica.
- Le 10 query sono scelte per le domande aperte dell'ablation: concetti di più parole (prossimità: q01, q23, q25),
  query che con AND trovano pochi articoli (q04, q13, q20), argomenti ampi (q02, q10, q27), una persona (q08).
  Sono poche: le differenze piccole tra configurazioni (pochi centesimi) non vanno considerate significative.

---

## 5. Ablation study

### Metodo
1. Si parte dalla **configurazione completa** (quella attuale).
2. Si cambia **una sola scelta alla volta**, lasciando tutto il resto uguale.
3. Si misurano **le stesse query** (stesso seme nel known-item, stesse query giudicate) e le stesse metriche.
4. Una scelta è utile se toglierla **peggiora** le metriche; se non cambia nulla, è una complicazione inutile.
5. Con poche query il rischio di overfitting è alto: scegliere i parametri su metà delle query e
   riportare i risultati sull'altra metà.

**Configurazione:** i boost dei campi e la prossimità sono in `SearchSettings` (default in
`application.properties`, prefisso `wikinews.search`). Boost 0 = campo escluso, `proximity-boost=0` = prossimità
disattivata. Per provare una variante senza toccare il codice basta passarla da riga di comando:

```bash
./mvnw test -Dtest=KnownItemEvaluationTest -DexcludedGroups=none -Dwikinews.search.name-boost=1
```

Il test stampa sempre la riga `Pesi: ...` con la configurazione usata: copiarla insieme ai risultati.
L'operatore si cambia con `-Dwikinews.search.and-operator=false` (OR).
Più proprietà si passano come argomenti `-D` separati (non dentro un'unica stringa, altrimenti Spring
riceve un solo valore e la conversione in numero fallisce).

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
| Completa (attuale) | 0,959 | 0,830 | 0,893 | 0,920 | 0,920 | 0,919 | |
| Titolo con boost 1 invece di 3 | 0,920 | 0,847 | 0,895 | | | | |
| − boost dei campi (tutti a 1) | 0,943 | 0,834 | 0,893 | 0,940 | 0,913 | 0,918 | |
| OR invece di AND | 0,924 | 0,585 | 0,786 | 0,920 | 0,923 | 0,929 | |
| − prossimità | 0,957 | 0,830 | 0,893 | 0,910 | 0,924 | 0,887 | |
| − categorie nella ricerca | 0,959 | 0,827 | 0,890 | 0,920 | 0,902 | 0,918 | |
| TF-IDF invece di BM25 | | | | | | | |
| `StandardAnalyzer` | | | | | | | |

---

## 6. Risultati raccolti finora

### Known-item (configurazione attuale, 500 articoli, seme 42, primi 100 risultati)

| Query | N. query | MRR | Success@1 | Success@10 | Non trovati |
|---|---|---|---|---|---|
| Titolo | 500 | 0,959 | 0,938 | 0,996 | 0 |
| 4 parole del testo | 429 | 0,830 | 0,795 | 0,893 | 20 |

### Ablation: boost del titolo (known-item, 2026-11-19)

| Boost titolo | MRR titolo | Success@1 titolo | MRR testo | Success@1 testo |
|---|---|---|---|---|
| 3 (attuale) | **0,959** | **0,938** | 0,830 | 0,795 |
| 1 | 0,920 | 0,880 | **0,847** | **0,818** |

Il boost del titolo aiuta chi cerca con le parole del titolo (+0,04 MRR) ma penalizza un po' chi cerca con
parole del testo (−0,02 MRR): premiando il titolo, gli articoli che hanno *nel titolo* quelle parole
superano l'articolo cercato, che le ha solo nel testo. È il compromesso atteso; serve la valutazione
per argomento (categorie o giudizi manuali) per decidere il valore finale.

### Ablation: operatore AND / OR (known-item, 2026-11-19)

| Operatore | MRR titolo | Success@1 titolo | MRR testo | Success@1 testo | Success@10 testo | Non trovati (testo) |
|---|---|---|---|---|---|---|
| AND (attuale) | **0,959** | **0,938** | **0,830** | **0,795** | **0,893** | 20 |
| OR | 0,924 | 0,906 | 0,585 | 0,480 | 0,786 | 28 |

Con OR basta una parola per essere trovati: per le query dal testo (4 parole generiche) molti articoli
che contengono *una sola* parola, ma nel titolo (boost 3), superano l'articolo cercato. L'effetto è molto
più grande di quello del boost del titolo (−0,245 MRR contro ±0,04).

**Attenzione, il known-item favorisce AND per costruzione:** le parole della query sono prese dall'articolo
cercato, quindi l'articolo le contiene *tutte* e AND non lo esclude mai. Nelle query reali l'utente può usare
parole che l'articolo non contiene (es. q13 "Mostra del **cinema** di Venezia": con AND trova solo 8 articoli,
perché molti scrivono "arte cinematografica"). Il vantaggio di AND va quindi confermato con le categorie
o con i giudizi manuali, guardando anche la Recall.

### Ablation: boost, prossimità e categorie (known-item, 2026-11-19)

| Configurazione | MRR titolo | Success@1 titolo | MRR testo | Success@1 testo |
|---|---|---|---|---|
| Completa (titolo 3, abstract 2, prossimità, categorie) | **0,959** | **0,938** | 0,830 | 0,795 |
| Titolo 1 (abstract 2) | 0,920 | 0,880 | **0,847** | **0,818** |
| Tutti i boost a 1 | 0,943 | 0,916 | 0,834 | 0,802 |
| Senza prossimità | 0,957 | 0,934 | 0,830 | 0,795 |
| Senza categorie nella ricerca | 0,959 | 0,936 | 0,827 | 0,790 |

- **Boost dei campi:** la configurazione attuale è la migliore per le query dal titolo; per quelle dal testo le
  differenze sono piccole (±0,02). Curioso: "titolo 1, abstract 2" è peggio di "tutti a 1" sul titolo (0,920 contro
  0,943), perché favorire l'abstract rispetto al titolo sposta il peso dove le parole del titolo non sono.
  Conta il **rapporto** tra i boost, non il singolo valore.
- **Prossimità:** effetto quasi nullo (−0,002). Il known-item non è adatto a misurarla: nelle query dal testo le
  4 parole sono scelte a caso e quasi mai vicine; nelle query dal titolo AND basta già a mettere l'articolo in alto.
  Va valutata con query di più parole che formano un concetto ("incidente ferroviario") nei giudizi manuali.
- **Categorie nella ricerca:** effetto quasi nullo (+0,003 sul testo). Non danneggiano; il loro ruolo vero è per le
  query per argomento ("sport", "politica"), che il known-item non contiene.

Conclusione provvisoria: con il known-item **solo l'operatore AND/OR ha un effetto netto**; boost, prossimità e
categorie spostano i valori di pochi millesimi o centesimi. Per decidere su questi serve la valutazione per argomento
(fatta: vedi sotto — la prossimità risulta utile, AND ha un costo localizzato).

### Valutazione per argomento con giudizi di rilevanza (2026-11-19)

**Metodo dei giudizi (da riportare nella relazione).**
I giudizi di rilevanza sono stati assegnati dal modello **Claude Opus 5.5** (Anthropic, identificativo
`claude-opus-5-5`, usato tramite Claude Code) il **19/11/2026**. Per ogni coppia query–articolo il modello ha letto
il bisogno informativo (`need` in `queries.tsv`), il titolo e l'inizio dell'articolo (abstract, o i primi ~260 caratteri
del testo se l'abstract manca), e ha assegnato un voto con la scala della sezione 4:
**2** = riguarda direttamente il bisogno, **1** = lo tocca in parte (pagine indice "Speciale", conseguenze indirette,
anteprime e curiosità), **0** = fuori tema. Non è stata usata nessuna regola automatica: ogni voto è stato dato
leggendo la singola riga.

- Pool: 10 query × primi 10 risultati di ognuna delle 5 configurazioni, uniti → **135 coppie**.
- Distribuzione dei voti: **99 × 2, 22 × 1, 14 × 0**.
- File di riferimento (versionato): `wikinews/src/test/resources/evaluation/qrels.tsv`.
  Un LLM non darebbe esattamente gli stessi voti a una seconda esecuzione: il dato riproducibile è il file.
- Verifica umana: **da fare** — vedi "Controllo dei giudizi" sotto.

Testo pronto per la relazione:
> I giudizi di rilevanza (135 coppie query–articolo, scala 0/1/2) sono stati assegnati dal modello Claude Opus 5.5
> (Anthropic) il 19/11/2026, sulla base del bisogno informativo di ciascuna query e del titolo e dell'inizio di ogni
> articolo. Un campione di 20 giudizi è stato verificato manualmente, con un accordo dell'X%.

**Risultati** (`./mvnw test -Dtest=RetrievalEvaluationTest -DexcludedGroups=none`):

| Configurazione | P@10 | Recall@100 | MAP | MRR | nDCG@10 |
|---|---|---|---|---|---|
| Attuale (AND, titolo 3, abstract 2, prossimità, categorie) | 0,920 | 0,977 | 0,920 | 1,000 | 0,919 |
| OR invece di AND | 0,920 | **1,000** | 0,923 | 1,000 | **0,929** |
| Senza prossimità | 0,910 | 0,977 | 0,924 | 1,000 | 0,887 |
| Boost tutti a 1 | **0,940** | 0,971 | 0,913 | 1,000 | 0,918 |
| Senza categorie | 0,920 | 0,977 | 0,902 | 1,000 | 0,918 |

**Cosa dicono:**
- **Prossimità — utile (opposto del known-item).** Togliendola nDCG@10 scende da 0,919 a 0,887. L'effetto è
  concentrato dove le parole formano un concetto: q10 "Palio di Siena" nDCG 0,709 → 0,477, q23 "incidente
  ferroviario" 1,000 → 0,887. Il known-item non lo vedeva perché usa parole scelte a caso. Esempio da citare.
- **AND contro OR — il costo di AND esiste ma è localizzato.** Su 9 query su 10 i risultati sono identici;
  su q20 "guerra Ucraina Russia" AND perde 3 articoli rilevanti su 13 (Recall@100 0,769 contro 1,000; nDCG 0,748
  contro 0,846), perché non contengono tutte e tre le parole. È il costo che il known-item non poteva misurare.
  Insieme al known-item (dove OR crolla: MRR 0,830 → 0,585) la conclusione è: AND è la scelta migliore in generale,
  ma penalizza le query con parole alternative. Una via di mezzo da citare come sviluppo futuro: "minimum should match"
  (es. almeno 2 parole su 3).
- **Boost dei campi — differenze minime** (P@10 +0,02 con tutti a 1, MAP −0,007): con 10 query non significative.
  Il boost del titolo si giustifica con il known-item (+0,016 MRR sul titolo).
- **Categorie nella ricerca — leggermente utili**: senza, MAP scende da 0,920 a 0,902.
- **MRR = 1 ovunque**: per tutte le query il primo risultato è rilevante in ogni configurazione.

**Limiti di questa valutazione:**
- Solo 10 query e pool profondo 10: molte query sono al "soffitto" (P@10 = 1 per 7 query su 10), quindi le
  differenze di pochi centesimi non sono significative. Contano gli effetti grandi e spiegabili (q10, q20, q23).
- Recall relativa ai rilevanti *conosciuti* nel pool, non a tutti quelli del dump.
- I giudizi sono di un LLM, che ha visto solo titolo e inizio dell'articolo; inoltre lo stesso assistente ha
  contribuito a sviluppare il sistema. Per questo serve il controllo umano su un campione.
- q13: tra i risultati di tutte le configurazioni ci sono 7 articoli fuori tema (mostre d'arte a Venezia, sciopero
  dello spettacolo): "mostra" e "Venezia" sono ambigui. Solo 5 rilevanti trovati: molti articoli sulla Mostra
  usano "arte cinematografica" e non "cinema".

**Controllo dei giudizi (da fare, ~10 minuti):** una persona del gruppo apre `qrels.tsv`, sceglie 20 righe a caso
(2 per query), copre la colonna `relevance`, dà il proprio voto leggendo `need` e articolo, e conta quante volte
coincide con quello del modello. Riportare l'accordo (es. "17/20 = 85%") nella frase per la relazione. Se l'accordo
è basso, correggere le righe in disaccordo e rilanciare il test.

Osservazioni sul known-item:
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
- **Bias di AND nel known-item:** l'articolo cercato contiene sempre tutte le parole della query, quindi
  il known-item non può mostrare il costo di AND (articoli rilevanti persi). Serve una valutazione per argomento.
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
