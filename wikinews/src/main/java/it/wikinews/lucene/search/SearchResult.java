package it.wikinews.lucene.search;

import java.util.Date;
import java.util.List;

/**
 * Un risultato della ricerca.
 *
 * @param id         "identifier" dell'articolo nel dump (stabile, usato per la valutazione)
 * @param title      titolo dell'articolo
 * @param url        link all'articolo su it.wikinews.org
 * @param snippet    estratto del testo con le parole cercate evidenziate (HTML già escapato)
 * @param date       data di ultima modifica (può essere null)
 * @param categories categorie dell'articolo
 * @param score      punteggio di rilevanza calcolato da Lucene
 */
public record SearchResult(String id, String title, String url, String snippet, Date date,
                           List<String> categories, float score) {
}
