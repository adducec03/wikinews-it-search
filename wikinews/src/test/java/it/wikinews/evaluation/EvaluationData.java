package it.wikinews.evaluation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lettura dei file della valutazione:
 * - queries.tsv: le query (colonne qid, query, need), nel classpath dei test;
 * - qrels.tsv:   i giudizi di rilevanza (colonne qid, id, relevance, ...), su disco.
 */
public final class EvaluationData {

    /** Dove si trova il file dei giudizi (percorso relativo alla cartella wikinews/). */
    public static final Path DEFAULT_QRELS = Path.of("src", "test", "resources", "evaluation", "qrels.tsv");

    private EvaluationData() {
        // classe di soli metodi statici
    }

    /**
     * File dei giudizi da usare: quello standard, oppure quello indicato con
     * -Devaluation.qrels=percorso (utile per provare un file diverso).
     */
    public static Path qrelsPath() {
        String custom = System.getProperty("evaluation.qrels");
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        return DEFAULT_QRELS;
    }

    /** Legge queries.tsv: qid -> testo della query, nell'ordine del file. */
    public static Map<String, String> readQueries() throws IOException {
        Map<String, String> queries = new LinkedHashMap<String, String>();

        InputStream input = EvaluationData.class.getResourceAsStream("/evaluation/queries.tsv");
        if (input == null) {
            throw new IOException("File evaluation/queries.tsv non trovato in src/test/resources");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // intestazione
            line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    String[] columns = line.split("\t");
                    queries.put(columns[0], columns[1]);
                }
                line = reader.readLine();
            }
        }
        return queries;
    }

    /**
     * Legge i giudizi: qid -> (id articolo -> voto 0/1/2).
     * Le colonne vengono cercate per nome nell'intestazione (qid, id, relevance),
     * quindi l'ordine delle colonne non conta. Le righe con voto vuoto (non ancora giudicate) sono ignorate.
     * Se il file non esiste restituisce una mappa vuota.
     */
    public static Map<String, Map<String, Integer>> readJudgments(Path file) throws IOException {
        Map<String, Map<String, Integer>> judgments = new LinkedHashMap<String, Map<String, Integer>>();
        if (!Files.exists(file)) {
            return judgments;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return judgments;
            }
            String[] headerColumns = header.split("\t");
            int qidColumn = findColumn(headerColumns, "qid", file);
            int idColumn = findColumn(headerColumns, "id", file);
            int relevanceColumn = findColumn(headerColumns, "relevance", file);

            int lineNumber = 1;
            String line = reader.readLine();
            while (line != null) {
                lineNumber++;
                // -1: mantiene anche le colonne vuote in fondo alla riga
                String[] columns = line.split("\t", -1);

                if (columns.length > relevanceColumn && !columns[relevanceColumn].isBlank()) {
                    String qid = columns[qidColumn].trim();
                    String id = columns[idColumn].trim();
                    int grade = parseGrade(columns[relevanceColumn].trim(), file, lineNumber);

                    Map<String, Integer> grades = judgments.get(qid);
                    if (grades == null) {
                        grades = new HashMap<String, Integer>();
                        judgments.put(qid, grades);
                    }
                    grades.put(id, grade);
                }
                line = reader.readLine();
            }
        }
        return judgments;
    }

    private static int findColumn(String[] headerColumns, String name, Path file) throws IOException {
        for (int i = 0; i < headerColumns.length; i++) {
            if (headerColumns[i].trim().equals(name)) {
                return i;
            }
        }
        throw new IOException("Colonna '" + name + "' mancante nell'intestazione di " + file);
    }

    private static int parseGrade(String value, Path file, int lineNumber) throws IOException {
        if (value.equals("0") || value.equals("1") || value.equals("2")) {
            return Integer.parseInt(value);
        }
        throw new IOException("Voto non valido '" + value + "' alla riga " + lineNumber + " di " + file
                + " (ammessi: 0, 1, 2)");
    }
}
