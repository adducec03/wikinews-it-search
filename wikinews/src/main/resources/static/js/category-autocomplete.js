// Autocompletamento del campo "Categoria" nella barra di ricerca.
// Mentre l'utente scrive, chiede al server i suggerimenti (GET /categories?q=...)
// e riempie il <datalist> collegato al campo.

document.addEventListener("DOMContentLoaded", function () {
    var categoryInput = document.getElementById("category-input");
    var categoryList = document.getElementById("category-list");

    // la pagina non contiene la barra di ricerca: niente da fare
    if (categoryInput === null || categoryList === null) {
        return;
    }

    var suggestUrl = categoryInput.getAttribute("data-suggest-url");
    var waitMs = 150;
    var timer = null;

    function showSuggestions(names) {
        categoryList.innerHTML = "";
        for (var i = 0; i < names.length; i++) {
            var option = document.createElement("option");
            option.value = names[i];
            categoryList.appendChild(option);
        }
    }

    function loadSuggestions() {
        var text = categoryInput.value.trim();

        if (text === "") {
            showSuggestions([]);
            return;
        }

        fetch(suggestUrl + "?q=" + encodeURIComponent(text))
            .then(function (response) {
                return response.json();
            })
            .then(function (names) {
                showSuggestions(names);
            })
            .catch(function () {
                // se il server non risponde, il campo resta un normale campo di testo
                showSuggestions([]);
            });
    }

    // aspettiamo che l'utente smetta di scrivere prima di chiamare il server
    categoryInput.addEventListener("input", function () {
        clearTimeout(timer);
        timer = setTimeout(loadSuggestions, waitMs);
    });
});
