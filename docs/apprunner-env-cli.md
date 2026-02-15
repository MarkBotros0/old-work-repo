# App Runner: variabili d'ambiente da CLI

Guida per **scaricare** le variabili attuali, **modificarle** (es. con i nuovi nomi) e **ricaricarle** sul servizio App Runner senza usare la console.

---

## Di che tipo è il tuo servizio?

Su App Runner un servizio può essere stato creato in due modi:

| Tipo | Significato | Dove sono le variabili |
|------|-------------|-------------------------|
| **Image Repository** | Deploy da **immagine Docker** (es. ECR). È il caso tipico quando buildi l’immagine e la pubblichi su ECR. | `SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables` |
| **Code Repository** | Deploy da **codice** (es. GitHub): App Runner fa build e deploy dal repo. | `SourceConfiguration.CodeRepository.CodeConfiguration.CodeConfigurationValues.RuntimeEnvironmentVariables` |

**Se le variabili che vuoi aggiornare sono quelle che vedi in console** (Configuration → Environment variables) **e il servizio è stato creato da un’immagine (ECR)**, allora il tuo caso è **Image Repository**. Lo script sotto è scritto per questo caso: non c’entra con “code repository” (GitHub). La sezione “Se il servizio usa CodeRepository” in fondo riguarda solo chi ha un servizio creato da repository di codice.

---

## 1. Scaricare le variabili attualmente presenti

Il comando `describe-service` restituisce la configurazione completa del **servizio** (immagine, env, ecc.). Per un servizio da **immagine (ECR)** le variabili d’ambiente sono in:

`SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables`

### Requisiti

- AWS CLI configurato con profilo/credenziali che possono leggere App Runner.
- `jq` installato (per estrarre il JSON).

### Comando per ottenere il servizio (ARN e nome)

```bash
# Elenca i servizi (opzionale, per trovare l'ARN)
aws apprunner list-services --region eu-central-1 --output table
```

### Scaricare la configurazione e estrarre le variabili in JSON

Sostituisci `SERVICE_ARN` con l'ARN del tuo servizio (es. `arn:aws:apprunner:eu-central-1:123456789012:service/pos-trx-ade-be/xxxxx`).

```bash
# Salva l'output completo (utile per eventuale update)
aws apprunner describe-service --service-arn "SERVICE_ARN" --region eu-central-1 > apprunner-service-full.json

# Estrai solo le variabili d'ambiente in formato chiave=valore (per ispezione)
aws apprunner describe-service --service-arn "SERVICE_ARN" --region eu-central-1 \
  --query 'Service.SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables' \
  --output json > apprunner-env-current.json
```

Il file `apprunner-env-current.json` avrà forma:

```json
{
  "BE_BASE_URL": "https://xxx.eu-central-1.awsapprunner.com",
  "AZURE_CLIENT_ID": "...",
  ...
}
```

(Se le variabili sono vuote o il path non esiste, il file potrebbe essere `null` o `{}`.)

### Script one-liner per export in formato “chiave=valore” (facile da editare)

Su Linux/macOS/Git Bash:

```bash
SERVICE_ARN="arn:aws:apprunner:eu-central-1:ACCOUNT:service/SERVICE_NAME/ID"
aws apprunner describe-service --service-arn "$SERVICE_ARN" --region eu-central-1 \
  --query 'Service.SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables' \
  --output json | jq -r 'to_entries | .[] | "\(.key)=\(.value)"' > apprunner-env.txt
```

Puoi poi aprire `apprunner-env.txt`, rinominare le chiavi (es. `AZURE_CLIENT_ID` → `DELOITTE_SSO_CLIENT_ID`) e riutilizzare i valori per il passo successivo.

---

## 2. File JSON template per le nuove variabili

Crea un file (es. `apprunner-env-new.json`) con le variabili con i **nuovi nomi**. Sostituisci i valori placeholder con quelli reali (anche copiandoli da `apprunner-env-current.json` dopo aver rinominato le chiavi).

```json
{
  "BE_BASE_URL": "https://nexi-be.testpos-noprod.com",
  "FE_BASE_URL": "https://nexi.testpos-noprod.com",
  "DB_HOST": "db-pos-nprod.c1ocgkekaqhr.eu-central-1.rds.amazonaws.com",
  "DB_PORT": "3306",

  "NEXI_OIDC_AUTHORIZATION_URI": "https://intapi.nexi.it/ics/nexiauth/openid/authorize",
  "NEXI_OIDC_TOKEN_URI": "https://intapi.nexi.it/ics/nexiauth/openid/token",
  "NEXI_OIDC_JWK_SET_URI": "https://intapi.nexi.it/nexi/.well-known/jwks.json",
  "NEXI_OIDC_CLIENT_ID": "YOUR_NEXI_CLIENT_ID",
  "NEXI_OIDC_CLIENT_SECRET": "YOUR_NEXI_CLIENT_SECRET",
  "NEXI_DB_NAME": "posappdb",
  "NEXI_DB_USER": "posappusr",
  "NEXI_DB_PASSWORD": "YOUR_NEXI_DB_PASSWORD",

  "AMEX_OIDC_AUTHORIZATION_URI": "YOUR_AMEX_AUTHORIZATION_URI",
  "AMEX_OIDC_TOKEN_URI": "YOUR_AMEX_TOKEN_URI",
  "AMEX_OIDC_JWK_SET_URI": "YOUR_AMEX_JWK_SET_URI",
  "AMEX_OIDC_CLIENT_ID": "YOUR_AMEX_CLIENT_ID",
  "AMEX_OIDC_CLIENT_SECRET": "YOUR_AMEX_CLIENT_SECRET",
  "AMEX_DB_NAME": "aziendab_posappdb",
  "AMEX_DB_USER": "aziendab_posappusr",
  "AMEX_DB_PASSWORD": "YOUR_AMEX_DB_PASSWORD",

  "DELOITTE_SSO_TENANT_ID": "YOUR_ENTRA_TENANT_UUID",
  "DELOITTE_SSO_CLIENT_ID": "YOUR_ENTRA_CLIENT_ID",
  "DELOITTE_SSO_CLIENT_SECRET": "YOUR_ENTRA_CLIENT_SECRET",

  "SPRING_PROFILES_ACTIVE": "dev",
  "APP_JWT_SECRET": "YOUR_BASE64_SECRET"
}
```

Se usi ancora le vecchie variabili (AZIENDAA_*, AZURE_*, ecc.), l’app le accetta grazie al fallback in `application-dev.yml`; puoi migrare alle nuove gradualmente copiando i valori (vedi sotto).

**Usare il template con il JSON scaricato:** il file `docs/apprunner-env-template.json` contiene tutte le variabili principali (SSO, DB, URL, JWT). Devi solo aggiornarne i valori usando il JSON scaricato, mappando le chiavi vecchie → nuove: `AZIENDAA_OIDC_*` → `NEXI_OIDC_*`, `AZIENDAA_DB_*` → `NEXI_DB_*`, `AZIENDAB_*` → `AMEX_*`, `AZURE_TENANT_ID` → `DELOITTE_SSO_TENANT_ID`, `AZURE_CLIENT_ID` → `DELOITTE_SSO_CLIENT_ID`, `AZURE_CLIENT_SECRET` → `DELOITTE_SSO_CLIENT_SECRET`. Se nel JSON scaricato hai altre variabili (S3_*, AWS_ECS_*, DB_HOST, DB_PORT) che vuoi mantenere, includile nel file finale prima dell'update.

---

## 3. Aggiornare il servizio con le variabili dal JSON

`update-service` richiede di passare l’intera **SourceConfiguration** (non solo le env). Procedura consigliata:

1. Ottenere la configurazione attuale con `describe-service`.
2. Sostituire o mergiare solo `ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables` con il contenuto del tuo JSON.
3. Chiamare `update-service` con la SourceConfiguration aggiornata.

Lo script aggiorna le **variabili d'ambiente del servizio** (quelle che vedi in console); è pensato per servizi da **immagine ECR** (Image Repository), non per Code Repository (GitHub).

Salva lo script come `update-apprunner-env.sh` (e rendilo eseguibile con `chmod +x update-apprunner-env.sh`).

```bash
#!/bin/bash
set -e
SERVICE_ARN="${1:?Usage: $0 <SERVICE_ARN> [env.json]}"
ENV_JSON="${2:-apprunner-env-new.json}"
REGION="${AWS_REGION:-eu-central-1}"

if [ ! -f "$ENV_JSON" ]; then
  echo "File $ENV_JSON non trovato."
  exit 1
fi

# 1) Scarica configurazione attuale
echo "Recupero configurazione servizio..."
aws apprunner describe-service --service-arn "$SERVICE_ARN" --region "$REGION" \
  --query 'Service' --output json > /tmp/apprunner-service.json

# 2) Prendi ImageRepository esistente e sostituisci solo RuntimeEnvironmentVariables
SOURCE_CONFIG=$(jq -n \
  --argjson svc "$(cat /tmp/apprunner-service.json)" \
  --argjson env "$(cat "$ENV_JSON")" \
  '($svc.SourceConfiguration.ImageRepository | .ImageConfiguration = ((.ImageConfiguration // {}) | .RuntimeEnvironmentVariables = $env)) | { ImageRepository: . }')

# 3) Update (solo source configuration; per altri campi usa --instance-configuration ecc.)
echo "Invio update al servizio..."
aws apprunner update-service \
  --service-arn "$SERVICE_ARN" \
  --source-configuration "$SOURCE_CONFIG" \
  --region "$REGION" \
  --output json

echo "Avviato. Verifica lo stato con: aws apprunner list-operations --service-arn $SERVICE_ARN --region $REGION"
```

Uso (Linux/macOS/Git Bash):

```bash
./update-apprunner-env.sh "arn:aws:apprunner:eu-central-1:ACCOUNT:service/SERVICE_NAME/ID" apprunner-env-new.json
```

**Su Windows:** `chmod` non esiste in PowerShell. Apri **Git Bash** (se hai Git per Windows) o **WSL** e lancia lo script con `bash` (non serve chmod):

```bash
bash update-apprunner-env.sh "arn:aws:apprunner:eu-central-1:ACCOUNT:service/SERVICE_NAME/ID" apprunner-env-new.json
```

Requisiti: AWS CLI e `jq` installati (in Git Bash puoi usare `jq` per Windows o installarlo con Chocolatey).

#### Se Bash è bloccato dai Criteri di gruppo (ERROR_ACCESS_DISABLED_BY_POLICY)

In alcuni ambienti aziendali l’esecuzione di Bash (Git Bash, WSL) è disabilitata dai Criteri di gruppo. In quel caso usa lo **script PowerShell** `update-apprunner-env.ps1`, che non richiede Bash né `jq`:

```powershell
.\update-apprunner-env.ps1 -ServiceArn "arn:aws:apprunner:eu-central-1:ACCOUNT:service/SERVICE_NAME/ID" -EnvJsonPath "apprunner-env-new.json"
```

Parametri opzionali: `-Region "eu-central-1"` (default), `-EnvJsonPath` (default: `apprunner-env-template.json`). Requisiti: **PowerShell 5.x o 7+** e **AWS CLI** installato e configurato (`aws configure`). Se l’esecuzione degli script è disabilitata, lancia prima:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

Poi esegui di nuovo `.\update-apprunner-env.ps1 ...`.

### Solo se il servizio è da Code Repository (GitHub)

Se il servizio è stato creato da **repository di codice** (GitHub, ecc.) e non da immagine ECR, il path delle variabili è diverso (`CodeRepository` invece di `ImageRepository`). In quel caso va adattato lo script (usa `CodeRepository` nella source configuration). Per il caso **Image Repository** (deploy da ECR) non serve modificare nulla.

### Verifica

Dopo l’update, l’operazione è asincrona. Controlla lo stato:

```bash
aws apprunner list-operations --service-arn "SERVICE_ARN" --region eu-central-1
```

Poi verifica che le variabili siano state applicate:

```bash
aws apprunner describe-service --service-arn "SERVICE_ARN" --region eu-central-1 \
  --query 'Service.SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables'
```

---

## Riepilogo comandi utili

| Obiettivo | Comando |
|-----------|---------|
| Elencare i servizi | `aws apprunner list-services --region eu-central-1` |
| Scaricare configurazione completa | `aws apprunner describe-service --service-arn ARN --region eu-central-1 > full.json` |
| Estrarre solo le env in JSON | `aws apprunner describe-service --service-arn ARN --region eu-central-1 --query 'Service.SourceConfiguration.ImageRepository.ImageConfiguration.RuntimeEnvironmentVariables' --output json > env.json` |
| Aggiornare le env (Bash) | `./update-apprunner-env.sh ARN apprunner-env-new.json` |
| Aggiornare le env (PowerShell, se Bash bloccato) | `.\update-apprunner-env.ps1 -ServiceArn ARN -EnvJsonPath apprunner-env-new.json` |
| Verificare operazione in corso | `aws apprunner list-operations --service-arn ARN --region eu-central-1` |

**Redirect URI negli IdP:** con i provider id rinominati (`oidc-amex`, `oidc-deloitte`) i path di callback sono:
- Amex: `{BE_BASE_URL}/api/login/oauth2/code/oidc-amex`
- Deloitte (Entra): `{BE_BASE_URL}/api/login/oauth2/code/oidc-deloitte`

Assicurati che in Entra ID (App registration → Authentication) e nel provider Amex siano registrate queste redirect URI. Vedi **docs/env-and-deployment.md**.
