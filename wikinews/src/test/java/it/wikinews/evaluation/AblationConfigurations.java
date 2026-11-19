package it.wikinews.evaluation;

import java.util.ArrayList;
import java.util.List;

import it.wikinews.lucene.search.SearchSettings;

/**
 * Le configurazioni confrontate nell'ablation study.
 *
 * Ognuna parte dalla configurazione "attuale" (quella di application.properties)
 * e cambia UNA sola scelta. Usate sia per costruire il pool da giudicare
 * (PoolBuilderTest) sia per calcolare le metriche (RetrievalEvaluationTest).
 */
public final class AblationConfigurations {

    public static final String CURRENT = "attuale";
    public static final String OR_OPERATOR = "OR invece di AND";
    public static final String NO_PROXIMITY = "senza prossimità";
    public static final String ALL_BOOSTS_ONE = "boost tutti a 1";
    public static final String NO_CATEGORIES = "senza categorie";

    private AblationConfigurations() {
        // classe di soli metodi statici
    }

    /** Nomi delle configurazioni, nell'ordine in cui vengono stampate. */
    public static List<String> names() {
        List<String> names = new ArrayList<String>();
        names.add(CURRENT);
        names.add(OR_OPERATOR);
        names.add(NO_PROXIMITY);
        names.add(ALL_BOOSTS_ONE);
        names.add(NO_CATEGORIES);
        return names;
    }

    /**
     * Crea la configurazione con il nome indicato.
     *
     * @param name    uno dei nomi restituiti da names()
     * @param current la configurazione attuale, da cui si parte
     */
    public static SearchSettings create(String name, SearchSettings current) {
        SearchSettings settings = new SearchSettings();
        settings.copyFrom(current);

        if (name.equals(CURRENT)) {
            // nessuna modifica
        } else if (name.equals(OR_OPERATOR)) {
            settings.setAndOperator(false);
        } else if (name.equals(NO_PROXIMITY)) {
            settings.setProximityBoost(0f);
        } else if (name.equals(ALL_BOOSTS_ONE)) {
            settings.setNameBoost(1f);
            settings.setAbstractBoost(1f);
            settings.setContentBoost(1f);
            settings.setCategoriesBoost(1f);
        } else if (name.equals(NO_CATEGORIES)) {
            settings.setCategoriesBoost(0f);
        } else {
            throw new IllegalArgumentException("Configurazione sconosciuta: " + name);
        }
        return settings;
    }
}
