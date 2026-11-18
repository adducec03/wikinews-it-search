package it.wikinews.lucene.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pesi usati per calcolare la rilevanza dei risultati.
 *
 * I valori di default sono quelli scelti per il sistema. Si possono cambiare senza
 * modificare il codice:
 * - in application.properties, per esempio  wikinews.search.name-boost=5
 * - da riga di comando, per esempio         -Dwikinews.search.name-boost=5
 * - nei test, con i metodi set... (utile per l'ablation study)
 *
 * I boost cambiano solo l'ORDINE dei risultati, non quali articoli vengono trovati.
 * Eccezioni: un campo con boost 0 viene escluso dalla ricerca, e l'operatore AND/OR
 * decide quali articoli vengono trovati.
 */
@Component
@ConfigurationProperties(prefix = "wikinews.search")
public class SearchSettings {

    // ---- operatore tra le parole della query ----

    /**
     * true  = AND: ogni parola deve comparire (in almeno un campo). Risultati più precisi, ma meno.
     * false = OR:  basta una parola. Più risultati; quelli con più parole hanno punteggio più alto.
     * L'utente può comunque scrivere AND / OR esplicitamente nella query.
     * NB: a differenza dei boost, questa scelta cambia QUALI articoli vengono trovati.
     */
    private boolean andOperator = true;

    // ---- boost dei campi: quanto vale una parola trovata in quel campo ----

    /** Parola trovata nel titolo. */
    private float nameBoost = 3f;

    /** Parola trovata nell'abstract. */
    private float abstractBoost = 2f;

    /** Parola trovata nel testo dell'articolo. */
    private float contentBoost = 1f;

    /** Parola trovata nelle categorie. */
    private float categoriesBoost = 1f;

    // ---- boost di prossimità: premia le parole della query vicine tra loro ----

    /**
     * Quante posizioni di distanza sono tollerate tra le parole.
     * Es. query "elezioni comunali roma": il testo "elezioni comunali a Roma" rientra (distanza 1),
     * il testo "elezioni comunali [...500 parole...] Roma" no.
     */
    private int proximitySlop = 3;

    /** Quanto vale il bonus quando le parole sono vicine. 0 = bonus di prossimità disattivato. */
    private float proximityBoost = 2f;

    public boolean isAndOperator() {
        return andOperator;
    }

    public void setAndOperator(boolean andOperator) {
        this.andOperator = andOperator;
    }

    public float getNameBoost() {
        return nameBoost;
    }

    public void setNameBoost(float nameBoost) {
        this.nameBoost = nameBoost;
    }

    public float getAbstractBoost() {
        return abstractBoost;
    }

    public void setAbstractBoost(float abstractBoost) {
        this.abstractBoost = abstractBoost;
    }

    public float getContentBoost() {
        return contentBoost;
    }

    public void setContentBoost(float contentBoost) {
        this.contentBoost = contentBoost;
    }

    public float getCategoriesBoost() {
        return categoriesBoost;
    }

    public void setCategoriesBoost(float categoriesBoost) {
        this.categoriesBoost = categoriesBoost;
    }

    public int getProximitySlop() {
        return proximitySlop;
    }

    public void setProximitySlop(int proximitySlop) {
        this.proximitySlop = proximitySlop;
    }

    public float getProximityBoost() {
        return proximityBoost;
    }

    public void setProximityBoost(float proximityBoost) {
        this.proximityBoost = proximityBoost;
    }

    /** Descrizione leggibile, da stampare insieme ai risultati della valutazione. */
    @Override
    public String toString() {
        String operator = "OR";
        if (andOperator) {
            operator = "AND";
        }
        return "operatore=" + operator
                + ", titolo=" + nameBoost
                + ", abstract=" + abstractBoost
                + ", contenuto=" + contentBoost
                + ", categorie=" + categoriesBoost
                + ", prossimità=" + proximityBoost + " (slop " + proximitySlop + ")";
    }
}
