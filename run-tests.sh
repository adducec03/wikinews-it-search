#!/bin/bash
# ---------------------------------------------------------------------------
# Esegue tutti i test del sistema Wikinews Search (Linux / macOS).
#
# Uso (dalla cartella principale del progetto):
#   ./run-tests.sh          tutti i test: funzionali + valutazione sull'indice completo
#   ./run-tests.sh quick    solo i test funzionali (veloci, non serve il dump)
#
# Fasi:
#   1. test funzionali e unitari (indice in memoria, pochi secondi)
#   2. valutazione sull'indice completo: known-item, valutazione per argomento,
#      efficienza (tempi e dimensione dell'indice), latenza per tipo di query
#   3. invarianti e ablation (PerformanceTest) sull'indice completo
# Le fasi 2 e 3 richiedono il dump in wikinews/data/ (vedi README.MD).
# ---------------------------------------------------------------------------

DUMP="wikinews/data/itwikinews_namespace_0_0.ndjson"
MODE="$1"

# esito di ogni fase, per il riepilogo finale
RESULTS=""
FAILED=0

run_step() {
    # $1 = nome della fase, gli argomenti successivi = parametri per Maven
    local name="$1"
    shift
    echo ""
    echo "=================================================================="
    echo " $name"
    echo "=================================================================="
    if (cd wikinews && sh ./mvnw -q test "$@"); then
        RESULTS="$RESULTS\n  [OK]      $name"
    else
        RESULTS="$RESULTS\n  [FALLITO] $name"
        FAILED=1
    fi
}

# lo script va lanciato dalla cartella principale del progetto
if [ ! -f "wikinews/mvnw" ]; then
    echo "Lanciare lo script dalla cartella principale del progetto (quella che contiene 'wikinews')."
    exit 1
fi

if ! command -v java > /dev/null 2>&1; then
    echo "Java non trovato: installare Java 21 (vedi README.MD)."
    exit 1
fi

# Fase 1: test funzionali. PerformanceTest viene saltato puntandolo a un indice inesistente:
# girerà nella fase 3 sull'indice completo.
run_step "1. Test funzionali e unitari" "-Dwikinews.index=target/nessun-indice"

if [ "$MODE" = "quick" ]; then
    echo ""
    echo "Modalità quick: valutazione sull'indice completo saltata."
elif [ ! -f "$DUMP" ]; then
    echo ""
    echo "Dump non trovato in $DUMP: valutazione sull'indice completo saltata."
    echo "Per eseguirla scaricare ed estrarre il dump (vedi README.MD)."
    RESULTS="$RESULTS\n  [SALTATO] 2-3. Valutazione sull'indice completo (manca il dump)"
else
    # Fase 2: test con tag "evaluation". Costruiscono l'indice completo in wikinews/target/evaluation-index.
    run_step "2. Valutazione sull'indice completo (qualità ed efficienza)" "-Dgroups=evaluation" "-DexcludedGroups=none"

    # Fase 3: invarianti e ablation, sull'indice costruito nella fase 2.
    run_step "3. Invarianti e ablation (PerformanceTest)" "-Dtest=PerformanceTest" "-Dwikinews.index=target/evaluation-index"
fi

echo ""
echo "=================================================================="
echo " RIEPILOGO"
echo "=================================================================="
echo -e "$RESULTS"
echo ""
echo "Report dei test:            wikinews/target/surefire-reports/"
echo "Risultati della valutazione: wikinews/target/evaluation/"

exit $FAILED
