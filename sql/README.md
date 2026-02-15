# Script SQL – indice

Tutti gli script SQL del progetto (esclusi quelli in `src/main/resources/sql/` e `docker/mysql/init/`) sono qui organizzati per uso.

---

## Cosa usare per cosa

| Cosa vuoi fare | Dove andare |
|----------------|-------------|
| **Creare un nuovo database da zero** (schema, utenti, procedure, indici) | **[initialization/Script_DB_Complete_Initialization.sql](initialization/Script_DB_Complete_Initialization.sql)** |
| Inserire dati statici (periodi, authority, error_type, ecc.) dopo l’init | **[initialization/Script_DB_Static_Data_Insert](initialization/Script_DB_Static_Data_Insert)** |
| Pulire output dopo interruzione, rilanciare ingestion, verificare stato, kill query bloccate, ecc. | Cartella **[operational/](operational/)** |
| Consultare script già inglobati nello script completo (init storico, grant, indici, fix one-off) | Cartella **[archived/](archived/)** |

---

## Struttura cartelle

```
sql/
├── README.md                    ← sei qui
├── initialization/              ← script per nuovo DB e dati statici
│   ├── Script_DB_Complete_Initialization.sql   ← UNICO script init da eseguire
│   ├── Script_DB_Initialization               ← storico (punto di partenza)
│   └── Script_DB_Static_Data_Insert            ← dati statici (dopo schema)
├── operational/                ← script operativi (cleanup, verify, kill, resume, …)
└── archived/                   ← script il cui contenuto è nello script completo
```

---

## Confronto con DDL esportato da DBeaver

Se estrai il DDL da DBeaver (tabelle + indici) da un DB già in uso, ottieni solo lo **stato attuale delle tabelle**. Lo **Script_DB_Complete_Initialization.sql** include tutto quello che serve per un nuovo DB da zero:

- **In più rispetto al solo DDL tabelle:** creazione database, utente **posappusr** (e relativi GRANT), utente **stg_admin** (DEFINER per `sp_clear_staging`), utente **posappusr_stg** (opzionale), **stored procedure** (`sp_clear_staging`, `sp_insert_merchants_from_staging`, `sp_insert_transactions_from_staging`), GRANT EXECUTE a posappusr, tabelle MEMORY di appoggio (TEMP_OUTPUT_*), **temp_inserted_transactions**, e indici creati in modo condizionale (create_index_if_not_exists).

- **Allineamenti fatti allo script:** la unique key di **TRANSACTION** include **fk_submission** (come nel DB reale); aggiunti gli indici **idx_transaction_submission_output** e **idx_resolved_transaction_current_submission_output** per le query di output.

Per un nuovo tenant (es. nuovo schema su stesso server) usare lo script completo e sostituire `posappdb`/`posappusr` con nome DB e utente del tenant.

---

## Altri percorsi SQL nel progetto

- **`src/main/resources/sql/staging_tables.sql`** – definizioni staging usate dall’applicazione; il contenuto è incluso in `Script_DB_Complete_Initialization.sql`.
- **`docker/mysql/init/01-init.sql`** – init DB in ambiente Docker.
