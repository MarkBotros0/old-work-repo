# Aggiorna le variabili d'ambiente del servizio App Runner (Image Repository / ECR).
# Uso: .\update-apprunner-env.ps1 -ServiceArn "arn:aws:apprunner:..." [-EnvJsonPath "apprunner-env-new.json"] [-Region "eu-central-1"]
# Requisiti: AWS CLI installato e configurato (aws configure).

param(
    [Parameter(Mandatory = $true)]
    [string] $ServiceArn,
    [string] $EnvJsonPath = "apprunner-env-template.json",
    [string] $Region = "eu-central-1"
)

if (-not (Test-Path $EnvJsonPath)) {
    Write-Error "File non trovato: $EnvJsonPath"
    exit 1
}

Write-Host "Recupero configurazione servizio..."
$serviceJson = aws apprunner describe-service --service-arn $ServiceArn --region $Region --query 'Service' --output json 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "describe-service fallito: $serviceJson"
    exit 1
}

$service = $serviceJson | ConvertFrom-Json
# Env come JSON grezzo su una sola riga (la CLI AWS da stdin su Windows può troncare su multilinea)
$envJsonRaw = (Get-Content $EnvJsonPath -Raw -Encoding UTF8).Trim() -replace '\r\n', '' -replace '\n', '' -replace '\r', ''

$imgRepo = $service.SourceConfiguration.ImageRepository
$port = $imgRepo.ImageConfiguration.Port
if (-not $port) { $port = "8080" }
# Escape per uso dentro stringa JSON: \ e "
function Escape-JsonString { param([string]$s) if (-not $s) { return "" }; ($s -replace '\\', '\\\\' -replace '"', '\"') }
$imageIdEscaped = Escape-JsonString $imgRepo.ImageIdentifier
$serviceArnEscaped = Escape-JsonString $ServiceArn
$portEscaped = Escape-JsonString ([string]$port)
$repoTypeEscaped = Escape-JsonString $imgRepo.ImageRepositoryType

# RuntimeEnvironmentSecrets (solo se presente): serializzazione minimale
$secretsJson = ""
$secrets = $imgRepo.ImageConfiguration.PSObject.Properties['RuntimeEnvironmentSecrets']
if ($secrets -and $secrets.Value) {
    $secretsJson = ",""RuntimeEnvironmentSecrets"":" + ($secrets.Value | ConvertTo-Json -Compress -Depth 3)
}
# Payload come stringa JSON (env da file già valido; evita "Invalid JSON" da ConvertTo-Json totale)
$payloadJson = "{""ServiceArn"":""$serviceArnEscaped"",""SourceConfiguration"":{""ImageRepository"":{""ImageIdentifier"":""$imageIdEscaped"",""ImageRepositoryType"":""$repoTypeEscaped"",""ImageConfiguration"":{""Port"":""$portEscaped"",""RuntimeEnvironmentVariables"":$envJsonRaw$secretsJson}}}}"

$payloadPath = (Join-Path (Get-Location) "apprunner-update-payload.json")
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($payloadPath, $payloadJson, $utf8NoBom)
$payloadPath = (Resolve-Path $payloadPath).Path
Write-Host "Payload scritto in: $payloadPath"
# Verifica che il JSON sia valido (il file resta per ispezione in caso di errore)
try {
    $null = Get-Content $payloadPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Write-Error "Payload JSON non valido: $_"
    Write-Host "Payload in: $payloadPath" -ForegroundColor Yellow
    exit 1
}

Write-Host "Invio update al servizio... (payload in $payloadPath)"
# file:// con slash in avanti (es. file://C:/path/file.json): formato accettato dalla CLI AWS su Windows
$payloadFileUrl = "file://" + ($payloadPath -replace '\\', '/')
$result = & aws apprunner update-service --cli-input-json $payloadFileUrl --region $Region --output json
$result
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Payload lasciato in: $payloadPath (aprilo per ispezionare il JSON inviato)" -ForegroundColor Yellow
    exit $LASTEXITCODE
}
Remove-Item $payloadPath -Force -ErrorAction SilentlyContinue

if ($LASTEXITCODE -eq 0) {
    Write-Host "Avviato. Verifica lo stato con: aws apprunner list-operations --service-arn $ServiceArn --region $Region"
} else {
    exit 1
}
