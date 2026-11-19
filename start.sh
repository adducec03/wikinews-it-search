#!/bin/bash
# Avvia l'applicazione Wikinews Search (Linux / macOS).
# All'avvio indicizza il dump e poi apre il sito su http://localhost:8080

DUMP="wikinews/data/itwikinews_namespace_0_0.ndjson"

if [ ! -f "$DUMP" ]; then
    echo "Dump non trovato in $DUMP"
    echo "Scaricarlo ed estrarlo come descritto nel README.MD (sezione Installazione)."
    exit 1
fi

cd wikinews

./mvnw spring-boot:run
