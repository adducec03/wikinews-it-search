package it.wikinews.importer;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@Component
public class JsonReader {
    public void read(Path jsonPath, Consumer<String> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    consumer.accept(line);
                }
            }
        }
    }
}