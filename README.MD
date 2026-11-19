# WikiNews

### Tecnologie utilizzate
- Java 21
- Springboot & Maven
- Apache Lucene
- Thymeleaf

### Come far partire l'applicazione
Scariricare il file compresso da [questo link](https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz), ed estrarre il contenuto in una cartella `wikinews/data`.
Poi usare lo script di partenza, dopo averlo reso eseguibile

Linux:
```
./start.sh
```
Windows:
```
./start.ps1
```

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
Il sistema viene con 2 tipologie di test:

La prima consiste in una serie di test per il funzionamento di tutte le singole fasi del programma. I test utilizzano articoli fatti appositamente per i test, così da poter confrontare i risultati. Questo potrebbe anche seguire la logica della separazione dei dati di test, anche se in questo caso non si parlare di LLM.

La seconda, invece, va a valutare con un'unico test il sistema nel suo complesso attraverso la search con 21  query ripetute 10 volte ciascuna per vedere il tempo di latenza, la variazione fra la prima e le altre volte, anche rispetto alle query in sè.

Si possono notere le sostanziali differenze fra il caricamento di tutto alla prima query in generale, ma anche della
prima passata di query per ciascuna. Poi anche sulla differenza di tempo della query "2 parole" che poi viene ripetuta con OR e AND che cambiano la velocità e il risutato del lavoro.

Si possono replicare i test su Linux
```
./start-test.sh
```

## Elementi :
### Analyzer
In questo progetto viene utilizzato un AnalyzerWrapper formato da `ItalianAnalyzer()` come analyzer principale, ovvero per i campi senza un analyzer specificato, e da un `categoryAnalyzer`. Quest'ultimo è un custom analyzer, mappato solo per il field *CATEGORIES* ed è formato da uno **StardardToken**, **LowerCaseFilter** e uno **StopFilter** con l'aggiunta delle stopWords "articoli" e "pubblicati". 

Viene creato in `AnalyzerFactory.java`



