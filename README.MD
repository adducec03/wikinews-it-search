# WikiNews

### Tecnologie utilizzate
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
L'utente scrive la query nella barra di ricerca (con eventuali filtri per categoria, date e ordinamento): il form invia una richiesta GET a `/search`, gestita da `SearchController`. Il controller passa i parametri a `SearchService`, che costruisce la query Lucene (`QueryFactory`: testo su titolo, abstract, contenuto e categorie con pesi diversi, più filtri su categoria e data) e la esegue sull'indice tramite `ArticleSearcher`, ordinando per rilevanza o per data. Per ogni risultato vengono generati uno snippet con le parole evidenziate (`UnifiedHighlighter`) e i dati dell'articolo (titolo, URL, data, categorie).

### Import e Indicizzazione
A tempo di avvio parte `indexStartup.run` con `ImportService.importArticle`. Questo metodo legge il json con gli alticoli, passa il `ArticleParcer.parce`, che legge le informazioni tramite le classi definite nel modello e infine indicizza con `ArticleIndexer.index` che per ogni Articolo individuato dal parcer agigunge il Documento con tutti i rispettivi Field. I campi analizzati sono indicati in `IndexFields.java` e sono : ID, URL, NAME, ABSTRACT, CONTENT, DATE_MODIFIED e CATEGORIES.


### Ricerca
La classe `SearchService.java` gestisce l'intero flusso della ricerca nel seguente modo: 

- `AnalyzerFactory.createAnalyzer()` : restituisce lo stesso [anaylzer](#analyzer) utilizzato in fase di indicizzazione 
- `QueryFactory.build(analyzer, text, category, from, to)` : crea la query passandola per un [parser](#parcer), sempre con l'analyzer
- `ArticleSearcher.search` : viene prima acquisito e poi rilasciato. Il sercher esegue la ricerca della query ottenuta, usando le librerie lucene. La ricerca può essere effettuata con sort o meno.
- `getSearchResults` : si dividono i risultati ottenuti singolarmente avendo diversi `SearchResults` con il proprio score di rilevanza
- `return new SearchResponse(...)` : crea la risposta da passare al controller cos' da visualizzare i risultati ottenuti dalla ricerca 


### Struttura web
Le operazioni di ricerca vengono invocate dal `SearchController.java`, con il metodo `SearchService.search`, che gestisce la query e tutti i parametri di filtro e ordinamento tramite un metodo `GET` che rimanda alle pagine Thymeleaf (`search.html`, con i fragment `header`, `searchBar` e `footer`), con lo stile in `static/css/style.css`. Il service restituisce `SearchResponce` nel model.
#### Paginazione e ricerca
I risultati vengono mostrati 10 in ogni pagina

## Test
Il sistema viene con 2 tipologie di test:

La prima consiste in una serie di test per il funzionamento di tutte le singole fasi del programma. I test utilizzano articoli fatti appositamente per i test, così da poter confrontare i risultati. Questo potrebbe anche seguire la logica della separazione dei dati di test, anche se in questo caso non si parlare di LLM.

La seconda, invece, va a valutare con un'unico test il sistema nel suo complesso attraverso la search con 21  query ripetute 10 volte ciascuna per vedere il tempo di latenza, la variazione fra la prima e le altre volte, anche rispetto alle query in sè.

| Query | Hit | Prima (ms) | Min (ms) | Media (ms) | Max (ms) |
| --- | --- | --- | --- | --- | --- |
| Termine singolo | 2 | 484.99 | 5.68 | 7.78 | 10.33 |
| Termine frequente | 82 | 51.56 | 21.11 | 37.37 | 67.72 |
| Altro termine | 14 | 19.15 | 14.26 | 17.46 | 28.96 |
| Nome proprio | 16 | 19.23 | 8.80 | 11.59 | 15.20 |
| Due termini | 3 | 12.41 | 6.31 | 8.75 | 11.33 |
| Frase esatta | 1 | 40.08 | 4.55 | 6.83 | 12.82 |
| \+ e - | 27 | 47.24 | 8.95 | 12.25 | 16.88 |
| Campo categories | 35 | 5.79 | 1.91 | 2.55 | 3.14 |
| 2 parole | 2 | 9.22 | 2.26 | 3.77 | 4.57 |
| 2 parole in OR | 2 | 4.27 | 2.21 | 3.21 | 3.97 |
| 2 parole in AND | 0 | 1.24 | 0.59 | 1.03 | 2.01 |
| Wildcard | 0 | 21.73 | 1.59 | 3.12 | 5.69 |
| Query lunga | 29 | 19.00 | 8.91 | 12.82 | 19.28 |
| Nessun risultato | 0 | 0.76 | 0.55 | 0.68 | 1.05 |
| Testo + categoria | 11 | 14.45 | 8.18 | 8.99 | 9.87 |
| Solo categoria | 35 | 5.31 | 1.84 | 2.20 | 2.70 |
| Testo + date | 4 | 17.57 | 4.63 | 7.01 | 8.97 |
| Solo date | 36 | 1.87 | 1.65 | 1.78 | 2.03 |
| Testo + cat + date | 0 | 2.18 | 1.67 | 1.93 | 2.10 |
| Ordinata per data | 2 | 38.05 | 2.23 | 3.64 | 6.84 |
| Tutti i documenti | 100 | 3.97 | 1.49 | 2.52 | 5.02 |

#### Riepilogo

| Indicatore | Valore |
| --- | --- |
| Ricerche misurate | 210 |
| Tempo medio | 7.49 ms |
| Ricerca più lenta | 67.72 ms |
| Ricerche al secondo (un thread) | 134 |

Quindi il sistema sembra riuscire. Si possono notere le sostanziali differenze fra il caricamento di tutto alla prima query in generale, ma anche della
prima passata di query per ciascuna. Poi anche sulla differenza di tempo della query "2 parole" che poi viene ripetuta con OR e AND che cambiano la velocità e il risutato del lavoro.

## Elemennti :
### Analyzer
In questo progetto viene utilizzato un AnalyzerWrapper formato da `ItalianAnalyzer()` come analyzer principale, ovvero per i campi senza un analyzer specificato, e da un `categoryAnalyzer`. Quest'ultimo è un custom analyzer, mappato solo per il field *CATEGORIES* ed è formato da uno **StardardToken**, **LowerCaseFilter** e uno **StopFilter** con l'aggiunta delle stopWords "articoli" e "pubblicati". 

Viene creato in `AnalyzerFactory.java`

### Parcer

## Sviluppi futuri
- Inizializzare i campi non indicizzati
- l'opzione "stavi cercando ..?"