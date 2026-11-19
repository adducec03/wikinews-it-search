# ---------------------------------------------------------------------------
# Esegue tutti i test del sistema Wikinews Search (Windows, PowerShell).
#
# Uso (dalla cartella principale del progetto):
#   .\run-tests.ps1          tutti i test: funzionali + valutazione sull'indice completo
#   .\run-tests.ps1 quick    solo i test funzionali (veloci, non serve il dump)
#
# Fasi:
#   1. test funzionali e unitari (indice in memoria, pochi secondi)
#   2. valutazione sull'indice completo: known-item, valutazione per argomento,
#      efficienza (tempi e dimensione dell'indice), latenza per tipo di query
#   3. invarianti e ablation (PerformanceTest) sull'indice completo
# Le fasi 2 e 3 richiedono il dump in wikinews\data\ (vedi README.MD).
#
# Nota: i parametri -D... sono tra virgolette perché PowerShell altrimenti
# spezza gli argomenti che contengono un punto (es. -Dwikinews.index=...).
# ---------------------------------------------------------------------------

param([string]$Mode = "")

$Dump = "wikinews\data\itwikinews_namespace_0_0.ndjson"
$Results = @()
$Failed = $false

function Run-Step {
    param([string]$Name, [string[]]$MavenArgs)

    Write-Host ""
    Write-Host "=================================================================="
    Write-Host " $Name"
    Write-Host "=================================================================="

    Push-Location wikinews
    & .\mvnw.cmd -q test @MavenArgs
    $exitCode = $LASTEXITCODE
    Pop-Location

    if ($exitCode -eq 0) {
        $script:Results += "  [OK]      $Name"
    } else {
        $script:Results += "  [FALLITO] $Name"
        $script:Failed = $true
    }
}

# lo script va lanciato dalla cartella principale del progetto
if (-not (Test-Path "wikinews\mvnw.cmd")) {
    Write-Host "Lanciare lo script dalla cartella principale del progetto (quella che contiene 'wikinews')."
    exit 1
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Java non trovato: installare Java 21 (vedi README.MD)."
    exit 1
}

# Fase 1: test funzionali. PerformanceTest viene saltato puntandolo a un indice inesistente:
# girerà nella fase 3 sull'indice completo.
Run-Step "1. Test funzionali e unitari" @("-Dwikinews.index=target/nessun-indice")

if ($Mode -eq "quick") {
    Write-Host ""
    Write-Host "Modalita' quick: valutazione sull'indice completo saltata."
} elseif (-not (Test-Path $Dump)) {
    Write-Host ""
    Write-Host "Dump non trovato in $Dump : valutazione sull'indice completo saltata."
    Write-Host "Per eseguirla scaricare ed estrarre il dump (vedi README.MD)."
    $Results += "  [SALTATO] 2-3. Valutazione sull'indice completo (manca il dump)"
} else {
    # Fase 2: test con tag "evaluation". Costruiscono l'indice completo in wikinews\target\evaluation-index.
    Run-Step "2. Valutazione sull'indice completo (qualita' ed efficienza)" @("-Dgroups=evaluation", "-DexcludedGroups=none")

    # Fase 3: invarianti e ablation, sull'indice costruito nella fase 2.
    Run-Step "3. Invarianti e ablation (PerformanceTest)" @("-Dtest=PerformanceTest", "-Dwikinews.index=target/evaluation-index")
}

Write-Host ""
Write-Host "=================================================================="
Write-Host " RIEPILOGO"
Write-Host "=================================================================="
foreach ($line in $Results) {
    Write-Host $line
}
Write-Host ""
Write-Host "Report dei test:             wikinews\target\surefire-reports\"
Write-Host "Risultati della valutazione: wikinews\target\evaluation\"

if ($Failed) {
    exit 1
}
exit 0
