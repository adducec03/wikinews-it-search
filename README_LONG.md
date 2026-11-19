# Wikinews IT Search — descrizione del progetto, valutazione e risultati

> **Nota per chi usa questo documento (persona o LLM).** È la base di conoscenza per scrivere la relazione e la
> presentazione del progetto. Contiene il funzionamento a grandi linee, le scelte principali, la strategia di test,
> le metriche, i risultati e le conclusioni. Tutti i numeri sono stati misurati sul dump completo tra il 18 e il
> 19/11/2026: usarli così come sono, senza arrotondarli in modo diverso né aggiungerne di nuovi. Le sezioni
> "Messaggi chiave" riassumono cosa dire in una slide. I dettagli operativi (comandi, file) sono in `TEST_NOTES.md`.

---

## 1. Il progetto in breve

**Obiettivo (traccia dell'Homework 4, Ingegneria dei Dati).**
1. Costruire un motore di ricerca con interfaccia Web sulle notizie in italiano di it.wikinews.org.
2. Definire e applicare una strategia di test e di valutazione.
3. Pubblicare il codice su GitHub con istruzioni di installazione e uso.

**Dati.** Dump ufficiale *Enterprise HTML* di it.wikinews del 20/03/2025: un file NDJSON (un articolo JSON per riga)
con titolo, abstract, corpo dell'articolo in HTML, categorie, URL, data di ultima modifica.

| Dato | Valore |
|---|---|
| Articoli nel dump | 12.261 (564 MB) |
| Articoli distinti (id) | 12.213 — 48 articoli compaiono due volte; la seconda copia è sempre la più recente |
| Articoli senza abstract / senza categorie | 266 / 23 |
| Categorie distinte | 5.964 |
| Periodo coperto | 2005 – 2025 |

**Tecnologie.** Java 21, Spring Boot (applicazione web), Apache Lucene 9.12 (indicizzazione e ricerca),
Jsoup (estrazione del testo dall'HTML), Thymeleaf (pagine web), JUnit 5 (test).

**Messaggi chiave.**
- Motore di ricerca completo sull'intero archivio di Wikinews in italiano (oltre 12.000 notizie, 20 anni).
- Valutato sia per efficienza sia per qualità dei risultati, con ablation study sulle scelte di progetto.

---

## 2. Architettura e funzionamento

```
dump NDJSON ──► lettura riga per riga ──► parsing (JSON + testo dall'HTML)
            ──► analisi del testo (analyzer italiano) ──► indice Lucene su disco
utente ──► pagina web ──► costruzione della query ──► ricerca nell'indice (BM25)
       ◄── risultati con snippet evidenziati, filtri, paginazione, suggerimenti
```

1. **Importazione** (all'avvio dell'applicazione): il dump viene letto una riga alla volta, senza caricarlo tutto in
   memoria; per ogni articolo si estrae il testo dall'HTML e si crea un documento Lucene.
2. **Indicizzazione**: ogni documento viene analizzato (tokenizzazione, minuscole, stop word, stemming) e scritto
   nell'indice invertito.
3. **Ricerca**: la query dell'utente passa per **lo stesso analyzer** usato in indicizzazione (condizione necessaria
   perché i termini cercati coincidano con quelli indicizzati), viene trasformata in una query Lucene ed eseguita
   con il modello di ranking BM25.
4. **Presentazione**: pagina web con risultati, snippet, filtri, ordinamento e paginazione.

---

## 3. Indicizzazione: scelte principali

**Campi del documento.**

| Campo | Tipo | Uso |
|---|---|---|
| Titolo, abstract, testo | testo analizzato | ricerca full-text, con pesi diversi |
| Categorie | testo analizzato | ricerca e filtro per categoria |
| Data di ultima modifica | numerico (punto + doc values) | filtro per intervallo di date e ordinamento per data |
| Id, URL | parola esatta (non analizzata) | identificazione dell'articolo e link alla fonte |

**Analisi del testo.**
- Testi: `ItalianAnalyzer` di Lucene → minuscole, rimozione delle stop word italiane, gestione delle elisioni
  ("l'Aquila" → "aquila"), stemming leggero (es. "elezione" ed "elezioni" diventano lo stesso termine).
- Categorie: analyzer dedicato senza stemming e con stop word aggiuntive "articoli" e "pubblicati". Motivo: la
  categoria tecnica "Pubblicati" è presente nel 90% degli articoli (11.066) e non dice nulla sul contenuto.

**Qualità dei dati.**
- Gli articoli con campi mancanti (266 senza abstract) vengono indicizzati comunque: in origine venivano scartati
  con un errore.
- I duplicati (48) vengono sostituiti: nell'indice resta una sola copia, la più recente (aggiornamento per id).
- Risultato: **12.261 articoli importati su 12.261, 0 errori, 12.213 documenti nell'indice**.

**Messaggi chiave.**
- Stesso analyzer in indicizzazione e in ricerca; analyzer italiano per il testo, analyzer dedicato per le categorie.
- Pulizia dei dati verificata: nessun articolo perso, nessun duplicato.

---

## 4. Ricerca: scelte principali

- **Ricerca su più campi con pesi (boost)**: una parola trovata nel titolo vale 3, nell'abstract 2, nel testo e
  nelle categorie 1. Il titolo riassume la notizia, quindi una corrispondenza lì è un segnale forte.
- **Operatore AND di default**: tutte le parole della query devono comparire (in almeno un campo). L'utente può
  comunque scrivere `OR` esplicitamente.
- **Bonus di prossimità**: un articolo in cui le parole della query compaiono vicine (al massimo 3 posizioni) riceve
  un punteggio extra; non esclude nessun articolo, cambia solo l'ordine.
- **Modello di ranking**: BM25 (default di Lucene, k1 = 1,2, b = 0,75).
- **Sintassi avanzata**: frase esatta `"..."`, parole obbligatorie/escluse `+` `-`, ricerca in un campo
  (`categories:sport`), caratteri jolly (`terrem*`).
- **Filtri**: per categoria e per intervallo di date (non modificano il punteggio, restringono i risultati).
- **Ordinamento**: per rilevanza o per data; se l'utente usa solo i filtri, senza testo, i risultati sono ordinati
  per data.
- **Snippet**: estratto del testo con le parole cercate evidenziate.
- **"Forse cercavi…"**: se una query non trova nulla, il sistema cerca parole simili presenti nell'indice
  (correzione ortografica, fino a 2 lettere di differenza) e propone la query corretta.
- **Configurabilità**: boost dei campi, operatore AND/OR e prossimità si cambiano da file di configurazione,
  senza modificare il codice. È ciò che ha reso possibile l'ablation study.

---

## 5. Interfaccia web

- Barra di ricerca con filtri (categoria, intervallo di date) e ordinamento (rilevanza / data).
- Risultati 10 per pagina: titolo con link all'articolo originale, data, snippet evidenziato, categorie cliccabili
  (un clic filtra per quella categoria), numero di risultati e tempo di risposta.
- Autocompletamento del campo categoria: mentre si scrive vengono proposte le categorie esistenti, prima quelle che
  iniziano con il testo digitato, poi quelle con una parola che inizia con il testo, poi le altre.
- Messaggio comprensibile in caso di query non valida; suggerimento "Forse cercavi…".
- HTML, CSS e JavaScript in file separati.

---

## 6. Strategia di test

La valutazione è organizzata su quattro livelli:

| Livello | Domanda | Come |
|---|---|---|
| **Correttezza** | Ogni componente fa quello che deve? | test funzionali su un piccolo indice di 7 articoli costruiti a mano, ognuno con un risultato atteso (analyzer, pesi, sintassi, filtri, ordinamento, paginazione, snippet, casi limite); test delle formule delle metriche su esempi calcolati a mano; test della configurazione |
| **Invarianti** | Il sistema si comporta in modo coerente sull'indice reale? | relazioni che devono valere sempre (vedi sotto) |
| **Efficienza** | Quanto è veloce e quanto occupa? | tempi di indicizzazione e di risposta, dimensione dell'indice, sull'indice completo |
| **Efficacia** | I risultati sono pertinenti? | metriche di Information Retrieval con due metodi di valutazione complementari, e ablation study |

**Invarianti verificate sull'indice reale** (tutte rispettate):
- stessa query → stessi risultati; maiuscole/minuscole irrilevanti ("europa" = "EUROPA" = 8.643 risultati);
- aggiungere un filtro non aumenta mai i risultati (es. "europa": 8.643 → 319 con categoria "Scienza e tecnologia");
- gli operatori rispettano la teoria degli insiemi: con A = "europa" (8.643) e B = "scuola" (364), A AND B = 293,
  A OR B = 8.714 = 8.643 + 364 − 293, A NOT B = 8.350, B NOT A = 71; la frase esatta trova meno risultati dell'AND;
- paginazione coerente: 5 pagine da 10 = 1 pagina da 50, senza duplicati.

I test lenti (quelli sull'indice completo) sono separati da quelli veloci e si lanciano a parte.

---

## 7. Metriche usate

**Efficacia** (qualità dei risultati; "rilevante" = articolo che risponde al bisogno dell'utente):

| Metrica | Significato |
|---|---|
| **Precision@10** | quota di risultati rilevanti nella prima pagina (la UI mostra 10 risultati) |
| **Recall@100** | quota dei rilevanti conosciuti che compare nei primi 100 risultati |
| **MRR** (Mean Reciprocal Rank) | media di 1/posizione del primo risultato rilevante: 1 = sempre primo |
| **MAP** (Mean Average Precision) | qualità dell'intera classifica: premia sia l'ordine sia la completezza |
| **nDCG@10** | come la precisione, ma con voti graduati (0/1/2) e più peso alle prime posizioni |
| **Success@k** | quota di query in cui l'articolo cercato è tra i primi k risultati |

**Efficienza**: tempo di indicizzazione e articoli al secondo, dimensione dell'indice, latenza delle ricerche
(media, mediana, 95° percentile = "il 95% delle ricerche risponde entro questo tempo"), ricerche al secondo.

**Copertura**: articoli importati, scartati, documenti nell'indice.

---

## 8. Come è stata misurata la qualità: due metodi complementari

Le metriche di qualità richiedono di sapere quali articoli sono rilevanti per ogni query. Sono stati usati due metodi.

**A. Known-item search (completamente automatico).**
Si scelgono a caso centinaia di articoli (seme fisso, quindi ripetibile); per ognuno si costruisce una query a
partire dall'articolo stesso e l'unico risultato giusto è quell'articolo. Due varianti: query = **titolo**
(l'utente conosce il titolo) e query = **parole del testo** (l'utente ricorda di cosa parlava). Misura la capacità
di ritrovare un articolo preciso.
- Pro: nessun lavoro manuale, centinaia di query.
- Limiti: le parole della query sono prese dall'articolo cercato, quindi l'articolo le contiene sempre tutte. Per
  questo il metodo **favorisce AND**, non vede l'utilità dello **stemming** e della **prossimità** (le parole
  scelte a caso non sono vicine tra loro).

**B. Valutazione per argomento con giudizi di rilevanza (pooling).**
10 query su argomenti reali (es. "terremoto Abruzzo", "guerra Ucraina Russia", "Palio di Siena",
"incidente ferroviario", "referendum"), ognuna con la descrizione del bisogno informativo. Per ogni query si
uniscono i primi 10 risultati di **tutte** le configurazioni confrontate (**pooling**, tecnica standard della
valutazione IR): un articolo trovato da una sola configurazione viene comunque giudicato, e il confronto è equo.
Totale: 135 coppie query–articolo, voto 0 (non rilevante), 1 (in parte), 2 (molto rilevante).
- **I giudizi sono stati assegnati dal modello Claude Opus 5.5 (Anthropic) il 19/11/2026**, leggendo il bisogno
  informativo, il titolo e l'inizio di ogni articolo (tecnica "LLM come giudice"). Distribuzione: 99 voti 2,
  22 voti 1, 14 voti 0. Il file dei giudizi è versionato, quindi i risultati sono riproducibili.
- Verifica umana su un campione di 20 giudizi: **[da completare — accordo X/20]**.
- Pro: misura la pertinenza per argomento e la Recall, che il known-item non vede.
- Limiti: poche query (differenze di pochi centesimi non significative); giudizi di un LLM che ha visto solo l'inizio
  degli articoli; Recall relativa ai rilevanti conosciuti nel pool.

**Messaggio chiave.** I due metodi si completano: su due scelte (prossimità e AND) danno indicazioni diverse, e
solo usandoli insieme si arriva alla conclusione corretta (sezione 11).

---

## 9. Risultati: efficienza

Macchina: Apple M1, 8 GB di RAM, Java 21. Indice completo (12.213 documenti). Latenza su 560 ricerche per
configurazione (28 query × 20 ripetizioni, dopo 5 giri di riscaldamento), eseguite come le esegue la pagina web
(prima pagina con snippet). Due esecuzioni: si riporta l'intervallo.

| | `SimpleTextCodec` (formato usato oggi) | Codec di default di Lucene |
|---|---|---|
| Tempo di indicizzazione | 17,0 – 17,4 s | **12,3 – 12,6 s** |
| Articoli al secondo | 703 – 720 | **975 – 996** |
| Dimensione dell'indice | 342,8 MB (61% del dump) | **37,5 MB (7% del dump)** |
| Latenza mediana | 5,4 – 5,7 ms | **2,1 – 2,2 ms** |
| Latenza p95 | 15,2 – 17,9 ms | **4,3 – 9,9 ms** |
| Ricerche al secondo (un thread) | 140 – 153 | **265 – 417** |

- Il sistema risponde in pochi millisecondi anche con il formato attuale (p95 sotto i 20 ms).
- `SimpleTextCodec` scrive l'indice in formato testo leggibile, utile solo per il debug: il codec di default produce un
  indice **9 volte più piccolo**, indicizza circa **1,4 volte più velocemente** e risponde **2–3 volte più velocemente**.
- Per tipo di query (indice completo): le più veloci sono quelle con soli filtri o ordinamento per data (1,5–5 ms),
  le più lente quelle con più clausole o con frasi (`+`/`-` 25,5 ms, query lunga 19,6 ms, frase esatta 15,9 ms).
- La primissima ricerca dopo l'avvio impiega circa 4 s (avvio della JVM e delle cache), la prima ricerca senza
  risultati circa 1,7 s (inizializzazione del correttore ortografico); poi pochi millisecondi.

---

## 10. Risultati: qualità della configurazione attuale

**Known-item search** (500 articoli a caso; l'articolo cercato è considerato "non trovato" oltre la posizione 100):

| Query | N. query | MRR | Success@1 | Success@10 |
|---|---|---|---|---|
| Titolo | 500 | 0,959 | 0,938 | 0,996 |
| 4 parole del testo non presenti nel titolo | 429 | 0,830 | 0,795 | 0,893 |

Un secondo test indipendente (200 articoli, query = titolo o prime 8 parole dell'abstract) conferma:
MRR 0,976 (titolo) e 0,908 (abstract), Success@10 100% e 99,5%.
La maggior parte degli articoli non trovati sono cronache sportive con lo stesso schema ("Serie A 2011-2012:
35ª giornata"), quasi identiche tra loro: la query è ambigua anche per una persona.

**Valutazione per argomento** (10 query, giudizi 0/1/2):

| P@10 | Recall@100 | MAP | MRR | nDCG@10 |
|---|---|---|---|---|
| 0,920 | 0,977 | 0,920 | 1,000 | 0,919 |

- In tutte le 10 query il primo risultato è rilevante (MRR = 1).
- Query più difficili: "Mostra del cinema di Venezia" (P@10 0,5: "mostra" e "Venezia" sono ambigui, molti
  risultati riguardano mostre d'arte) e "guerra Ucraina Russia" (Recall 0,77, vedi AND/OR).

---

## 11. Ablation study

**Metodo**: si parte dalla configurazione del sistema e si cambia **una sola scelta alla volta**, misurando le stesse
metriche sulle stesse query.

### 11.1 Risultati principali (configurazione reale come base)

MRR = known-item (titolo / parole del testo); P@10, MAP, nDCG@10 = valutazione per argomento.

| Configurazione | MRR titolo | MRR testo | P@10 | Recall@100 | MAP | nDCG@10 |
|---|---|---|---|---|---|---|
| **Attuale** (AND, boost 3/2/1/1, prossimità, categorie) | **0,959** | 0,830 | 0,920 | 0,977 | 0,920 | 0,919 |
| OR invece di AND | 0,924 | **0,585** | 0,920 | **1,000** | 0,923 | 0,929 |
| Senza prossimità | 0,957 | 0,830 | 0,910 | 0,977 | 0,924 | **0,887** |
| Tutti i boost a 1 | 0,943 | 0,834 | 0,940 | 0,971 | 0,913 | 0,918 |
| Titolo con boost 1 (abstract 2) | 0,920 | 0,847 | — | — | — | — |
| Senza categorie nella ricerca | 0,959 | 0,827 | 0,920 | 0,977 | 0,902 | 0,918 |

### 11.2 Altre ablation (test del gruppo con baseline diversa)

Un secondo insieme di ablation, sullo stesso indice completo, usa il known-item con 200 articoli (query = titolo /
prime 8 parole dell'abstract) e una baseline **con OR e senza prossimità**, diversa dalla configurazione reale.
I confronti interni sono validi, i valori assoluti non vanno confrontati con la tabella 11.1.

| Scelta | Variante | MRR titolo | MRR abstract | Osservazione |
|---|---|---|---|---|
| Modello di ranking | **BM25** (baseline) | 0,923 | 0,685 | |
| | TF-IDF (`ClassicSimilarity`) | 0,981 | 0,309 | ottimo sui titoli, pessimo su query più lunghe |
| | Language model Jelinek-Mercer | 0,934 | 0,763 | leggermente migliore di BM25 |
| | Language model Dirichlet | 0,849 | 0,561 | peggiore |
| Parametri BM25 | 9 combinazioni k1 ∈ {0,5; 1,2; 2,0}, b ∈ {0; 0,75; 1} | 0,909 – 0,940 | 0,654 – 0,699 | poco sensibile: i valori di default vanno bene |
| Analyzer | **ItalianAnalyzer** (baseline) | 0,923 | 0,685 | |
| | StandardAnalyzer (niente stemming né stop word) | 0,936 | 0,710 | leggermente migliore nel known-item, ma trova meno: "scuola" 263 risultati contro 364 |
| Stop word sulle categorie | con "pubblicati" escluso (baseline) | 0,923 | 0,685 | `categories:pubblicati` trova 0 articoli invece di 11.032: la categoria tecnica non inquina i risultati |
| Boost | titolo molto alto (10/2/1/1) | 0,987 | 0,269 | un peso eccessivo sul titolo rovina le altre query |
| Operatore | AND | 0,976 | 0,880 | conferma: AND molto meglio di OR (0,923 / 0,685) |
| Campi cercati | senza categorie | 0,921 | 0,606 | "europa" passa da 8.643 a 647 risultati: la categoria "Europa" è su 8.547 articoli |

### 11.3 Cosa dice ogni scelta

- **Operatore AND — confermato, è la scelta con l'effetto più grande.** Con OR basta una parola per essere trovati e
  articoli poco pertinenti superano quello cercato: MRR sulle parole del testo da 0,830 a 0,585. Il costo di AND è
  reale ma localizzato: su "guerra Ucraina Russia" perde 3 articoli rilevanti su 13 che non contengono tutte e tre le
  parole (Recall 0,77 contro 1,00); sulle altre 9 query AND e OR danno gli stessi risultati.
- **Prossimità — utile, ma visibile solo con la valutazione per argomento.** Senza, nDCG@10 scende da 0,919 a 0,887;
  l'effetto è forte dove le parole formano un concetto ("Palio di Siena" 0,709 → 0,477, "incidente ferroviario"
  1,000 → 0,887). Nel known-item l'effetto è nullo, perché le parole della query sono scelte a caso.
- **Boost dei campi — da tenere, conta il rapporto tra i pesi.** Il boost del titolo aiuta chi cerca con parole del
  titolo (MRR 0,959 contro 0,943 con tutti a 1) e penalizza appena chi cerca con parole del testo; un peso eccessivo
  (titolo 10) fa crollare le query sul contenuto. Sulla valutazione per argomento le differenze sono di pochi centesimi.
- **Categorie nella ricerca libera — leggermente utili, con un effetto collaterale.** Senza, il MAP scende da 0,920 a
  0,902; ma rendono molto ampie le query su nomi geografici che sono anche categorie ("europa").
- **Modello di ranking — BM25 confermato.** È robusto su query corte e lunghe; TF-IDF crolla sulle query lunghe; il
  language model Jelinek-Mercer è di poco migliore solo nel known-item. I parametri k1 e b incidono poco.
- **Analyzer italiano — confermato per l'uso reale, non dimostrato dal known-item.** Il known-item usa parole prese
  dall'articolo, quindi lo stemming non può aiutare (StandardAnalyzer è di poco migliore). Lo stemming però aumenta
  i risultati trovati per argomento ("scuola": 364 contro 263), cosa che conta per un utente reale.
- **Stop word "pubblicati" sulle categorie — confermata**: esclude una categoria presente nel 90% degli articoli.
- **Codec — da cambiare**: il codec di default è migliore su tutti i fronti (sezione 9).

---

## 12. Conclusioni

1. Il sistema indicizza l'intero archivio senza perdere articoli (12.261 su 12.261) e risponde in pochi millisecondi.
2. La qualità è alta: l'articolo cercato per titolo è primo nel 94% dei casi; nelle ricerche per argomento il primo
   risultato è sempre pertinente e 9 risultati su 10 della prima pagina sono rilevanti (P@10 = 0,92).
3. Le scelte di progetto sono state verificate una per una: **AND**, **prossimità**, **boost dei campi**, **BM25** e
   **stop word sulle categorie** sono confermati; il **codec** di debug va sostituito.
4. **Lezione di metodo**: metodi di valutazione diversi possono portare a conclusioni diverse (prossimità e AND).
   Il known-item è economico e adatto a molte query, ma misura un solo tipo di ricerca; la valutazione per argomento
   cattura la pertinenza e la Recall. Usarli insieme è stato necessario per decidere correttamente.

---

## 13. Limiti e sviluppi futuri

**Limiti della valutazione.**
- Valutazione per argomento con sole 10 query: differenze di pochi centesimi non sono significative.
- Giudizi di rilevanza assegnati da un LLM (verifica umana su campione da completare); il modello ha visto solo
  titolo e inizio degli articoli.
- Il known-item favorisce AND e non misura stemming e prossimità.
- Le misure di tempo variano tra esecuzioni e dipendono dalla macchina.
- L'analyzer non è stato confrontato con la valutazione per argomento.

**Sviluppi futuri.**
- "Minimum should match" (es. almeno 2 parole su 3) come compromesso tra AND e OR.
- Codec di default al posto di `SimpleTextCodec`; indice persistente invece di ricostruirlo a ogni avvio.
- Categorie indicizzate anche come valori esatti, per un filtro preciso ("Italia" oggi trova anche "Calcio in
  Italia") e per mostrare i conteggi per categoria.
- Più query giudicate, giudizi di più persone e misura dell'accordo tra giudici.
- Sinonimi (USA / Stati Uniti, ONU / Nazioni Unite) e gestione degli accenti.

---

## 14. Stato al 19/11/2026 (cose aperte)

- Verifica umana dei giudizi di rilevanza: da fare (20 righe, ~10 minuti); inserire l'accordo nella sezione 8.
- 4 test funzionali falliscono perché il test crea il servizio di ricerca senza il servizio dei suggerimenti
  (correzione di una riga nel test).
- Nel test delle ablation della sezione 11.2 la baseline (OR, senza prossimità) non coincide con il sistema reale:
  il test lo segnala con un controllo che fallisce.
- L'applicazione usa ancora `SimpleTextCodec` e ricostruisce l'indice a ogni avvio.
- La tabella delle latenze nel `README.MD` viene da una vecchia misura su 100 articoli: va sostituita con la sezione 9.
