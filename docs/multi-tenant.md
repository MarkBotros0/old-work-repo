# Multi-tenant: chiarimenti e flusso

## 1. Bootstrap tenant (solo avvio applicazione)

**Cosa significa:** All'avvio, Spring deve eseguire operazioni che richiedono una connessione JDBC *prima* che arrivi qualsiasi richiesta HTTP (es. creazione tabelle Spring Session, rilevamento del driver). In quel momento non esiste un "utente" né un "host": non c'è contesto tenant.

**Cosa fa l'app:** Usa un tenant di configurazione detto **bootstrap** (es. `nexi`) solo per aprire *una* connessione a *un* database e completare l'init. È un uso puramente tecnico.

**Rischi:** Nessuno per le richieste utente. Le richieste utente **non** sono mai servite con il tenant di bootstrap. Il bootstrap non determina "su quale tenant parte l'app": l'app non "parte su Azienda A". Serve solo a soddisfare Spring durante l'avvio.

**Alternativa "tenant finto":** Si potrebbe definire un tenant solo per l'init (stesso URL DB di un tenant esistente ma con nome logico tipo `bootstrap`). Il comportamento sarebbe identico: una connessione temporanea per l'init. Non cambia la sicurezza.

---

## 2. Come viene deciso il tenant a runtime

Il tenant **non** viene deciso solo dall'URL/host. Viene deciso **dalla combinazione di**:

1. **Host della richiesta** – per utenti **non** autenticati (es. scelta SSO): l'host indica quale set di SSO mostrare (A vs B).
2. **Risposta SSO (token)** – per utenti autenticati il tenant è **fissato dalla sessione**, che a sua volta è stata impostata **al login** in base al token (ruoli/claim).

Quindi:
- **Navigazione su URL Nexi** → vedi SSO Nexi; quando fai login, il token restituisce (es.) ruoli tipo `NEXI_POSAPP_STG_SPR`.
- **Il prefisso del ruolo** (es. `NEXI_`) viene usato per ricavare il tenant (es. `nexi`) e **salvarlo in sessione**.
- Da quel momento, **tutta la sessione** di quell'utente è legata al tenant A: dati, DB, contesto. Un utente con sessione A non può "passare" a B cambiando URL, perché il tenant in sessione è quello derivato dal token.

In sintesi:
- **Host** → scelta SSO e (opzionale) controllo che l'URL sia coerente con il tenant in sessione.
- **Token (ruoli/claim)** → definisce il **tenant della sessione** e quindi il tenant effettivo per tutte le richieste autenticate.

---

## 3. Flusso tecnico

1. **Richiesta senza sessione / non autenticata**  
   - Tenant dal **host** (es. `nexi-be.testpos-noprod.com` → `nexi`).  
   - Se l'host non è riconosciuto → 403.  
   - La pagina mostrata (es. scelta SSO) è quella del tenant derivato dall'host.

2. **Login SSO (callback)**  
   - Tenant ancora dall'host (per mostrare la pagina giusta).  
   - Dopo autenticazione riuscita: dal **token** si ricava il tenant (claim `tenant_id` o prefisso ruoli tipo `NEXI_` → `nexi`).  
   - Il tenant viene **salvato in sessione** (es. attributo `SESSION_TENANT_ID`).  
   - Redirect al FE.

3. **Richieste successive (utente autenticato)**  
   - Tenant letto dalla **sessione** (impostata al login).  
   - Viene usato per DataSource, contesto, ecc.  
   - (Opzionale) Se l'host è riconosciuto e diverso dal tenant in sessione → 403 (evita uso URL B con sessione A).

In questo modo il tenant è "embedded" nella configurazione della sessione utente (derivato dall'SSO) e non si mischia con sessioni/tenant di altri utenti.

---

## 4. Aggiungere un nuovo tenant (es. nuova azienda)

Quando arriva una nuova azienda/tenant, basta questa sequenza (niente modifiche al codice se usi variabili d'ambiente):

1. **Database**  
   Eseguire lo script di creazione DB per la nuova azienda (vedi [sql/README.md](../sql/README.md)): nuovo database, utente, grant. Es. `nuovotenant_posappdb`, `nuovotenant_posappusr`.

2. **Lista tenant e configurazione**  
   In **application-dev.yml** (e application-local.yml se usi profilo local):
   - **DB (comune a tutti i tenant):** `DB_HOST`, `DB_PORT`. **Per tenant:** `AZIENDAC_DB_NAME`, `AZIENDAC_DB_USER`, `AZIENDAC_DB_PASSWORD` (es. per aziendac). L'URL JDBC è costruito come `jdbc:mariadb://${DB_HOST}:${DB_PORT}/${AZIENDAC_DB_NAME}?...`.
   - Aggiungere sotto **multi-tenant.tenants** un blocco per il nuovo tenant (es. `aziendac`) usando queste variabili per database-name, database-url, database-username, database-password; e **sso.providers** (es. `oidc-aziendac`, `oidc-azure`).
   - In **spring.security.oauth2.client** aggiungere **provider** e **registration** per il nuovo OIDC (es. `oidc-aziendac`), con variabili d'ambiente (es. `AZIENDAC_OIDC_*`).
   - In **multi-tenant.provider-display-names** aggiungere l'etichetta del pulsante SSO (es. `oidc-aziendac: "Nome Azienda"`).
   - In **SecurityConfiguration**: aggiungere `requestMatchers` per il nuovo provider/callback solo se non già coperti da pattern esistenti.

3. **Host / DNS**  
   Se l'URL è per tenant specifico: il tenant viene dall'host (es. `aziendac-be.testpos-noprod.com` → `aziendac`). Se il pattern in TenantIdentificationFilter è già generico, basta configurare DNS per il nuovo sottodominio.

**Riepilogo:** script DB → aggiornare lista tenant e config (yml + variabili d'ambiente) → DNS/host se serve. I pulsanti SSO sono dinamici: ogni tenant vede solo i provider configurati, con etichette da **provider-display-names** (es. Nexi, Amex, Deloitte).

---

## 5. Batch ECS (ingestion) multi-tenant

Il processo di ingestion batch (profilo `batch`) gira su ECS ed è avviato da una **Lambda** quando viene creato un file `.eot` in una cartella S3. Per il multi-tenant si usano **due Lambda**, una per tenant:

- **Lambda Nexi**: trigger su `nexi/input/`; alla creazione di un `.eot` lancia il task ECS con variabili d’ambiente **fisse** per Nexi (`TENANT_ID=nexi`, DB e S3 del tenant Nexi).
- **Lambda Amex**: trigger su `amex/input/`; stessa cosa per tenant Amex.
- **Un run = un tenant**: il task riceve `TENANT_ID`, `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` e le cartelle S3 (`S3_BUCKET_INPUT_FOLDER`, ecc.) per quel tenant.
- L’app con profilo `batch` usa **TenantAwareDataSource** e **TenantContext**; `BatchIngestionRunner` imposta il contesto da `TENANT_ID`, così il DB e le cartelle S3 sono quelli corretti.

**Struttura S3:**

- `s3://bucket/nexi/input/` – trigger Lambda Nexi  
- `s3://bucket/amex/input/` – trigger Lambda Amex  
- (e relative `input-loaded/`, `output/` per ogni tenant)

**Variabili da passare al task (Container Override):** `TENANT_ID`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `S3_BUCKET_*`.

Dettaglio setup (due Lambda, trigger, override): vedi [lambda-ecs-batch-multitenant.md](lambda-ecs-batch-multitenant.md).

---

## 6. Workaround temporaneo: URL di test (aziendab/aziendaa)

In **ambiente di test** gli host sono ancora i vecchi nomi: **aziendab** (per Amex) e **aziendaa** (per Nexi), mentre nel codice e nel JWT SSO i tenant sono **amex** e **nexi**. Per evitare 401 (tenant nel JWT ≠ tenant dall’host) si usa la mappa alias in `TenantConfiguration.resolveTenantAlias()` (aziendaa→nexi, aziendab→amex), così il confronto host vs token riesce.

**È una soluzione tampone:** quando gli URL di test saranno sostituiti da quelli target (es. `amex-be.testpos-noprod.com`, `nexi-be.testpos-noprod.com`), si può **rimuovere** questo workaround (mapping alias e relativi usi nei filter/handler).
