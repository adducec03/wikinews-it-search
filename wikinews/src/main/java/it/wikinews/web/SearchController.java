package it.wikinews.web;

import java.time.LocalDate;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import it.wikinews.search.SearchService;

@Controller
public class SearchController {

    private static final int PAGE_SIZE = 10;

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/search")
    public String search(@RequestParam(name = "q", defaultValue = "") String q,
                         @RequestParam(name = "category", defaultValue = "") String category,
                         @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(name = "sort", defaultValue = "relevance") String sort,
                         @RequestParam(name = "page", defaultValue = "1") int page,
                         Model model) {
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("sort", sort);

        boolean nothing = q.isBlank() && category.isBlank() && from == null && to == null;
        if (nothing) {
            return "search";
        }
        try {
            model.addAttribute("response",
                    searchService.search(q, category, from, to, "date".equals(sort), page, PAGE_SIZE));
        } catch (ParseException e) {
            model.addAttribute("error", "Query non valida: controlla virgolette e parentesi.");
        } catch (Exception e) {
            model.addAttribute("error", "Errore durante la ricerca: " + e.getMessage());
        }
        return "search";
    }
}
