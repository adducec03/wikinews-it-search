package it.wikinews;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.wikinews.lucene.AnalyzerFactory;
import it.wikinews.lucene.search.QueryFactory;
import it.wikinews.lucene.search.SearchSettings;

/**
 * Verifica che i pesi di SearchSettings vengano davvero usati nella query Lucene.
 * Si controlla la forma testuale della query (query.toString()), per esempio
 * "(name:terremot)^3.0" = parola "terremot" (dopo lo stemming) nel titolo con boost 3.
 */
@DisplayName("QueryFactory: pesi configurabili con SearchSettings")
class QueryFactorySettingsTest {

    private final Analyzer analyzer = AnalyzerFactory.createAnalyzer();

    private String buildQuery(SearchSettings settings, String text) throws Exception {
        Query query = QueryFactory.build(analyzer, settings, text, "", null, null);
        return query.toString();
    }

    @Test
    @DisplayName("I valori di default sono quelli scelti per il sistema")
    void defaultBoosts() throws Exception {
        String query = buildQuery(new SearchSettings(), "terremoto");

        assertTrue(query.contains("(name:terremot)^3.0"), query);
        assertTrue(query.contains("(abstract:terremot)^2.0"), query);
        assertTrue(query.contains("content:terremot"), query);
        assertTrue(query.contains("categories:terremoto"), query);
    }

    @Test
    @DisplayName("Un boost cambiato viene usato nella query")
    void customFieldBoost() throws Exception {
        SearchSettings settings = new SearchSettings();
        settings.setNameBoost(7f);

        String query = buildQuery(settings, "terremoto");

        assertTrue(query.contains("(name:terremot)^7.0"), query);
    }

    @Test
    @DisplayName("Boost 0 esclude il campo dalla ricerca")
    void zeroBoostRemovesField() throws Exception {
        SearchSettings settings = new SearchSettings();
        settings.setCategoriesBoost(0f);

        String query = buildQuery(settings, "terremoto");

        assertFalse(query.contains("categories:"), query);
        assertTrue(query.contains("name:"), query);
    }

    @Test
    @DisplayName("Prossimità: attiva di default, disattivata con boost 0")
    void proximityBoost() throws Exception {
        // la prossimità si vede come frase con slop: "... ..."~3
        String withProximity = buildQuery(new SearchSettings(), "elezioni comunali");
        assertTrue(withProximity.contains("~3"), withProximity);

        SearchSettings settings = new SearchSettings();
        settings.setProximityBoost(0f);
        String withoutProximity = buildQuery(settings, "elezioni comunali");
        assertFalse(withoutProximity.contains("~"), withoutProximity);
    }

    @Test
    @DisplayName("Slop di prossimità configurabile")
    void customProximitySlop() throws Exception {
        SearchSettings settings = new SearchSettings();
        settings.setProximitySlop(10);

        String query = buildQuery(settings, "elezioni comunali");

        assertTrue(query.contains("~10"), query);
    }

    @Test
    @DisplayName("Tutti i campi a 0 è una configurazione non valida")
    void allFieldsDisabled() {
        SearchSettings settings = new SearchSettings();
        settings.setNameBoost(0f);
        settings.setAbstractBoost(0f);
        settings.setContentBoost(0f);
        settings.setCategoriesBoost(0f);

        assertThrows(IllegalStateException.class, () -> buildQuery(settings, "terremoto"));
    }
}
