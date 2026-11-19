# Avvia l'applicazione Wikinews Search (Windows, PowerShell).
# All'avvio indicizza il dump e poi apre il sito su http://localhost:8080

$Dump = "wikinews\data\itwikinews_namespace_0_0.ndjson"

if (-not (Test-Path $Dump)) {
    Write-Host "Dump non trovato in $Dump"
    Write-Host "Scaricarlo ed estrarlo come descritto nel README.MD (sezione Installazione)."
    exit 1
}

cd wikinews; .\mvnw.cmd spring-boot:run
