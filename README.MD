# WikiNews

Motore di ricerca full-text sulle notizie in italiano di [it.wikinews.org](https://it.wikinews.org)
(dump del 20/03/2025, 12.261 articoli), con interfaccia web per ricerca, filtri e snippet evidenziati.

### Tecnologie utilizzate
- Java 21, Spring Boot & Maven
- Apache Lucene 9.12
- Jsoup (estrazione del testo dall'HTML)
- Thymeleaf
- JUnit 5

## Installazione

### Prerequisiti
- **Java 21** (JDK). Per controllare la versione installata: `java -version`.
  Se manca, si può scaricare per esempio da [Adoptium](https://adoptium.net/) (Temurin 21).
- **Git**, per scaricare il progetto.
- Non serve installare Maven: il progetto include il wrapper (`mvnw` / `mvnw.cmd`), che lo scarica da solo al primo uso.
- Circa 1 GB di spazio libero (dump estratto 564 MB, più l'indice e le dipendenze).

Il dump di Wikinews non è nel repository (è troppo grande): va scaricato a parte,
[da questo link](https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz)
(archivio di 73 MB), ed estratto nella cartella `wikinews/data/`. Il file estratto deve essere
`wikinews/data/itwikinews_namespace_0_0.ndjson`. I comandi qui sotto fanno tutto questo.

### Linux / macOS
Da un terminale:
```bash
git clone https://github.com/adducec03/wikinews-it-search.git
cd wikinews-it-search

# scarica ed estrai il dump in wikinews/data/
mkdir -p wikinews/data
curl -L -o itwikinews.tar.gz https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz
tar -xzf itwikinews.tar.gz -C wikinews/data
rm itwikinews.tar.gz

# rendi eseguibili gli script (solo la prima volta) e avvia
chmod +x start.sh run-tests.sh wikinews/mvnw
./start.sh
```

### Windows
Da **PowerShell** (Windows 10 e successivi includono già `curl.exe` e `tar`):
```powershell
git clone https://github.com/adducec03/wikinews-it-search.git
cd wikinews-it-search

# scarica ed estrai il dump in wikinews\data\
mkdir wikinews\data
curl.exe -L -o itwikinews.tar.gz https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz
tar -xzf itwikinews.tar.gz -C wikinews\data
del itwikinews.tar.gz

# avvia
powershell -ExecutionPolicy Bypass -File .\start.ps1
```
`-ExecutionPolicy Bypass` serve perché Windows, di default, blocca l'esecuzione degli script PowerShell scaricati.

### Uso
- Al primo avvio Maven scarica le dipendenze (qualche minuto, solo la prima volta), poi l'applicazione
  indicizza tutto il dump (circa 15 secondi). Quando nel terminale compare `Indicizzazione completata!`
  aprire il browser su **http://localhost:8080**.
- L'indice viene ricostruito a ogni avvio. Per fermare l'applicazione: `Ctrl+C` nel terminale.
- Esempi di ricerca: `terremoto Abruzzo`, `"crisi economica"` (frase esatta), `+calcio -serie`
  (parola obbligatoria / esclusa), `categories:sport` (ricerca in un campo), `terrem*` (carattere jolly).
  Dal pannello "Filtri e ordinamento" si filtra per categoria (con suggerimenti) e per intervallo di date,
  e si ordina per rilevanza o per data.

### Configurazione
In `wikinews/src/main/resources/application.properties`:
- `wikinews.data.maxArticle`: numero massimo di articoli da indicizzare (per uno sviluppo più veloce si può abbassare);
- `wikinews.search.*`: pesi dei campi, operatore AND/OR e bonus di prossimità usati dalla ricerca.

### Problemi frequenti
- **`Dump non trovato`** all'avvio: il file non è in `wikinews/data/itwikinews_namespace_0_0.ndjson`
  (controllare di averlo estratto nella cartella giusta).
- **`permission denied: ./start.sh`** (Linux/macOS): eseguire `chmod +x start.sh run-tests.sh wikinews/mvnw`.
- **Porta 8080 occupata**: chiudere l'altro programma che la usa, oppure avviare dalla cartella `wikinews` con
  `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090` e aprire http://localhost:9090.

## Funzionamento

### Import e Indicizzazione
A tempo di avvio parte `indexStartup.run` con `ImportService.importArticle`. Questo metodo legge il json con gli alticoli, passa il `ArticleParcer.parce`, che legge le informazioni tramite le classi definite nel modello e infine indicizza con `ArticleIndexer.index` che per ogni Articolo individuato dal parcer agigunge il Documento con tutti i rispettivi Field. I campi analizzati sono indicati in `IndexFields.java` e sono : ID, URL, NAME, ABSTRACT, CONTENT, DATE_MODIFIED e CATEGORIES.


### Ricerca
La classe `SearchService.java` gestisce l'intero flusso della ricerca nel seguente modo: 

- `AnalyzerFactory.createAnalyzer()` : restituisce lo stesso [anaylzer](#analyzer) utilizzato in fase di indicizzazione 
- `QueryFactory.build(analyzer, text, category, from, to)` : crea la query passandola per un [parser](#parcer), sempre con l'analyzer
- `ArticleSearcher.search` : viene prima acquisito e poi rilasciato. Il sercher esegue la ricerca della query ottenuta, usando le librerie lucene. La ricerca può essere effettuata con sort o meno.
- `getSearchResults` : si dividono i risultati ottenuti singolarmente avendo diversi `SearchResults` con il proprio score di rilevanza, generando uno snippet con le parole evidenziate (`UnifiedHighlighter`) e i dati dell'articolo
- `return new SearchResponse(...)` : crea la risposta da passare al controller cos' da visualizzare i risultati ottenuti dalla ricerca 


### Struttura web
Le operazioni di ricerca vengono invocate dal `SearchController.java`, con il metodo `SearchService.search`, che gestisce la query e tutti i parametri di filtro e ordinamento tramite un metodo `GET` che rimanda alle pagine Thymeleaf (`search.html`, con i fragment `header`, `searchBar` e `footer`), con lo stile in `static/css/style.css`. Il service restituisce `SearchResponce` nel model.
#### Paginazione e ricerca
I risultati vengono mostrati 10 in ogni pagina. Sono disponibili i filtri per categoria, data (come intervallo) e la possibilità di ordinamento per rilevanza o per data.
Il filtro per categoria ha una piccolo pannello di consiglio di categorie presenti nei documenti. 

Nella ricerca è stato aggiunta un'opzione per consiglio di stringa inserita, il "forse cercavi..."

## Test

### Eseguire tutti i test
Lo script `run-tests` esegue tutti i test del sistema e alla fine stampa un riepilogo con l'esito di ogni fase.
Va lanciato dalla cartella principale del progetto.

Linux / macOS:
```bash
./run-tests.sh          # tutti i test (circa 2 minuti, serve il dump)
./run-tests.sh quick    # solo i test funzionali (pochi secondi, il dump non serve)
```
Windows (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1          # tutti i test
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1 quick    # solo i test funzionali
```

Lo script è diviso in tre fasi:

| Fase | Cosa esegue | Serve il dump |
|---|---|---|
| 1. Test funzionali e unitari | `WikinewsApplicationTests` (13 test su un piccolo indice in memoria con articoli costruiti a mano: analyzer, pesi dei campi, sintassi, filtri, ordinamento, paginazione, snippet, casi limite), `IrMetricsTest` (formule delle metriche su esempi calcolati a mano), `QueryFactorySettingsTest` (configurazione della ricerca) | no |
| 2. Valutazione sull'indice completo | known-item search, valutazione per argomento con giudizi di rilevanza, efficienza (tempo di indicizzazione, dimensione dell'indice, latenza), latenza per tipo di query. Costruisce l'indice completo in `wikinews/target/evaluation-index` | sì |
| 3. Invarianti e ablation | `PerformanceTest`: relazioni che devono valere sempre sull'indice reale (filtri, operatori booleani, paginazione) e ablation su pesi, campi, operatore, modello di ranking, parametri BM25, analyzer | sì |

Se il dump manca, le fasi 2 e 3 vengono saltate e lo script lo segnala. I risultati vengono salvati in:
- `wikinews/target/surefire-reports/`: esito di ogni test;
- `wikinews/target/evaluation/`: tabelle della valutazione (known-item, metriche per argomento, efficienza).

Per lanciare un singolo test di valutazione, dalla cartella `wikinews`:
```bash
./mvnw test -Dtest=KnownItemEvaluationTest -DexcludedGroups=none
```
(`-DexcludedGroups=none` serve perché i test sull'indice completo, con tag `evaluation`, sono esclusi dal normale
`./mvnw test`, che esegue solo quelli veloci.)

### Metriche e risultati principali
- **Copertura**: 12.261 articoli indicizzati su 12.261, 0 errori, 12.213 documenti (i 48 duplicati sono sostituiti dalla versione più recente).
- **Efficienza** (Apple M1, codec di default di Lucene): indicizzazione completa in circa 12 s, indice di 37,5 MB,
  latenza mediana circa 2 ms e 95° percentile sotto i 10 ms.
- **Qualità**:
  - known-item search (500 articoli che cercano se stessi): con la query uguale al titolo l'articolo è primo nel 94% dei casi (MRR 0,959);
  - valutazione per argomento (10 query, giudizi di rilevanza 0/1/2): P@10 0,92, MAP 0,92, nDCG@10 0,92, MRR 1.
- **Ablation**: l'operatore AND e il bonus di prossimità sono le scelte che incidono di più; pesi dei campi,
  parametri BM25 e categorie spostano i risultati di pochi centesimi; il codec di default rende l'indice 9 volte
  più piccolo e le ricerche 2–3 volte più veloci rispetto a `SimpleTextCodec`.

Metodologia, tabelle complete e limiti della valutazione: `README_LONG.md` (descrizione del progetto e risultati)
e `TEST_NOTES.md` (note dettagliate sui test).

## Elemennti :
### Analyzer
In questo progetto viene utilizzato un AnalyzerWrapper formato da `ItalianAnalyzer()` come analyzer principale, ovvero per i campi senza un analyzer specificato, e da un `categoryAnalyzer`. Quest'ultimo è un custom analyzer, mappato solo per il field *CATEGORIES* ed è formato da uno **StardardToken**, **LowerCaseFilter** e uno **StopFilter** con l'aggiunta delle stopWords "articoli" e "pubblicati". 

Viene creato in `AnalyzerFactory.java`

### Parcer
...

## Sviluppi futuri
- "Minimum should match" (es. almeno 2 parole su 3) come compromesso tra AND e OR.
- Indice persistente, invece di ricostruirlo a ogni avvio.
- Categorie indicizzate anche come valori esatti, per un filtro preciso e per mostrare i conteggi per categoria.
- Sinonimi (USA / Stati Uniti, ONU / Nazioni Unite) e gestione degli accenti.