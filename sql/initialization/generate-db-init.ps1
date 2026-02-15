# Genera un file SQL di inizializzazione DB con i placeholder sostituiti per il tenant.
# Uso: .\generate-db-init.ps1 -Tenant amex -AppPassword "xxx" [-StgAdminPassword "yyy"] [-StgPassword "zzy"] [-OutPath "Script_DB_Complete_Initialization_amex.sql"]
# Il file generato va eseguito con un client MySQL (Workbench, DBeaver, mysql CLI) come utente admin.

param(
    [Parameter(Mandatory = $true)]
    [string] $Tenant,
    [Parameter(Mandatory = $true)]
    [string] $AppPassword,
    [string] $StgAdminPassword = 'CHANGE_ME_STG_ADMIN_PASSWORD',
    [string] $StgPassword = 'CHANGE_ME_STG_PASSWORD',
    [string] $OutPath = ""
)

function Escape-SqlString {
    param([string]$s)
    if (-not $s) { return "" }
    $s -replace "'", "''"
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TemplatePath = Join-Path $ScriptDir "Script_DB_Complete_Initialization.sql"
if (-not (Test-Path $TemplatePath)) {
    Write-Error "Template non trovato: $TemplatePath"
    exit 1
}

$dbName = "${Tenant}_posappdb"
$appUser = "${Tenant}_posappusr"
$stgAdminUser = "${Tenant}_stg_admin"
$stgUser = "${Tenant}_posappusr_stg"

if (-not $OutPath) {
    $OutPath = Join-Path $ScriptDir "Script_DB_Complete_Initialization_${Tenant}.sql"
}

$content = Get-Content -Path $TemplatePath -Raw -Encoding UTF8
$content = $content -replace '__DB_NAME__', $dbName
$content = $content -replace '__APP_USER__', $appUser
$content = $content -replace '__APP_PASSWORD__', (Escape-SqlString $AppPassword)
$content = $content -replace '__STG_ADMIN_USER__', $stgAdminUser
$content = $content -replace '__STG_ADMIN_PASSWORD__', (Escape-SqlString $StgAdminPassword)
$content = $content -replace '__STG_USER__', $stgUser
$content = $content -replace '__STG_PASSWORD__', (Escape-SqlString $StgPassword)

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($OutPath, $content, $utf8NoBom)
Write-Host "Generato: $OutPath"
Write-Host "  DB: $dbName  User: $appUser  StgAdmin: $stgAdminUser  StgUser: $stgUser"
