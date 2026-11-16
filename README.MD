# WikiNews

## Tecnologie utilizzate
- Springboot & Maven
- Apache Lucene
- Thymeleaf

## Come far partire l'applicazione
Scariricare il file compresso da [questo link](https://dumps.wikimedia.org/other/enterprise_html/runs/20250320/itwikinews-NS0-20250320-ENTERPRISE-HTML.json.tar.gz), ed estrarre il contenuto in una cartella `wikinews/data`.
Poi usare lo script di partenza, dopo averlo reso eseguibile
Linux:
```
./start.sh
```

## Funzionamento
L'utente scrive la query nella barra di ricerca (con eventuali filtri per categoria, date e ordinamento): il form invia una richiesta GET a `/search`, gestita da `SearchController`. Il controller passa i parametri a `SearchService`, che costruisce la query Lucene (`QueryFactory`: testo su titolo, abstract, contenuto e categorie con pesi diversi, più filtri su categoria e data) e la esegue sull'indice tramite `ArticleSearcher`, ordinando per rilevanza o per data. Per ogni risultato vengono generati uno snippet con le parole evidenziate (`UnifiedHighlighter`) e i dati dell'articolo (titolo, URL, data, categorie).

### Indicizzazione
I campi dell'indicizzazione sono : ... (come indicato in `IndexFields.java`)

### Struttura web
I risultati, paginati a 10 per volta, tornano al controller e vengono mostrati dalle pagine Thymeleaf (`search.html`, con i fragment `header`, `searchBar` e `footer`), con lo stile in `static/css/style.css`.

## Sviluppi futuri
- Scrivere lo script ./start.sh
- Bisogna sviluppare i test
- Inizializzare i campi non indicizzati
