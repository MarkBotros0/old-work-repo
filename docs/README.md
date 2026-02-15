# Documentazione progetto

Indice della documentazione. Tutti i file `.md` del progetto (eccetto il README principale) sono qui organizzati per argomento.

---

## Documentazione principale (da usare per primo)

| File | Contenuto |
|------|-----------|
| **[database-ingestion.md](database-ingestion.md)** | **Unica guida DB e ingestion**: script di init, setup utenti, staging, flusso ingestion, validazione, indici, troubleshooting. Usare questa per tutto ciò che riguarda database e caricamento dati. |
| **[testing.md](testing.md)** | **Come testare gli sviluppi**: avvio in locale (dev/local/Docker), test multi-tenant (localhost vs Host header), 403 tenant sconosciuto, endpoint utili, SSO e claim tenant_id, variabili d’ambiente. |
| **[multi-tenant.md](multi-tenant.md)** | **Multi-tenant**: bootstrap tenant (solo init), come viene deciso il tenant (host + SSO/sessione), flusso tecnico (sessione legata al token). |
| **[aws/guida-aws-cli-okta.md](aws/guida-aws-cli-okta.md)** | Guida operativa AWS CLI e Okta. |

---

## Documentazione archiviata (riferimento storico)

Nella cartella ** [archived/](archived/) ** trovi i documenti che sono stati accorpati in *database-ingestion.md* o che descrivono fix/analisi/implementazioni già integrate in codice o script. Utili per approfondimenti o contesto.

- Setup STG, staging, step ingestion, flusso validazione, ottimizzazioni ERROR_RECORD, pulizia output  
- Approccio lazy output, fix loop output, test output  
- ECS batch, isolamento profili, configurazione heap, out of memory, lock timeout  
- Logging, cast/deduplicazione, performance, confronto approcci  
- Istruzioni duplicazione task, implementazioni ECS, prompt FE  

Per il lavoro quotidiano usa **database-ingestion.md** e **aws/guida-aws-cli-okta.md**; consulta `archived/` solo se serve dettaglio su un tema specifico.
