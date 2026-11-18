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
 * Eccezione: un campo con boost 0 viene escluso dalla ricerca.
 */
@Component
@ConfigurationProperties(prefix = "wikinews.search")
public class SearchSettings {

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
        return "titolo=" + nameBoost
                + ", abstract=" + abstractBoost
                + ", contenuto=" + contentBoost
                + ", categorie=" + categoriesBoost
                + ", prossimità=" + proximityBoost + " (slop " + proximitySlop + ")";
    }
}
