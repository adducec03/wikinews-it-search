package it.wikinews.config;

import it.wikinews.importer.ImportService;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class IndexStartup implements CommandLineRunner {

    private final ImportService importService;

    public IndexStartup(ImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Avvio indicizzazione Wikinews..."); 
        importService.importArticles();
        System.out.println("Indicizzazione completata!");
    }
}