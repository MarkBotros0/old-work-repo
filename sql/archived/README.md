# Script SQL archiviati

Questa cartella contiene gli script SQL **inglobati** in `Script_DB_Complete_Initialization.sql`.

## Perché sono qui

Il file **`Script_DB_Complete_Initialization.sql`** (nella root del progetto) è lo script unico di inizializzazione che riunisce:

- Lo schema base (da `Script_DB_Initialization`)
- Le tabelle di staging e le stored procedure (contenuto equivalente a `src/main/resources/sql/staging_tables.sql`)
- Utenti, grant e stored procedure (Create_STG_Admin_User, Grant_Execute, Grant_Permissions_stg, Grant_Create_Temp_Tables, Create_temp_inserted_transactions)
- Tabelle di appoggio output (Create_Output_Helper_Table)
- Tutti gli indici di performance (Performance_Indexes, Staging_Performance_Indexes, Validation_Performance_Indexes, Batch_Performance_Optimization, Create_Output_Performance_Indexes, Create_ErrorRecord_Index, Output_Performance_Optimization)
- Aggiornamenti schema (Update_Transaction_Unique_Key_Add_TipoPag, Update_Error_Types come sezione commentata)

Gli script in questa cartella sono stati **spostati qui** per ordine: non vanno più eseguiti singolarmente per un nuovo ambiente, ma restano disponibili per riferimento e per interventi mirati (es. Fix_IMP_OPE_Decimal, DB_Fix_Connection_Issues).

## Cosa resta fuori dalla cartella

- **`Script_DB_Initialization`** – script originale (punto di partenza storico)
- **`Script_DB_Complete_Initialization.sql`** – script unico da usare per inizializzare un nuovo database
- **`Script_DB_Static_Data_Insert`** – dati statici (da eseguire dopo lo schema, se previsto)
- **`src/main/resources/sql/staging_tables.sql`** – copia di riferimento per l’applicazione; il suo contenuto è incluso in `Script_DB_Complete_Initialization.sql`

## Script one-off / manutenzione

Non fanno parte dell’inizializzazione e vanno usati solo quando serve:

- `Script_Fix_IMP_OPE_Decimal.sql` – correzione dati `imp_ope` già importati (one-off)
- `Script_DB_Fix_Connection_Issues.sql` – diagnostica, timeout, lock (manutenzione)

Gli altri script rimasti nella root (Cleanup_*, Verify_*, Kill_*, ecc.) sono operativi o di verifica e non sono stati inglobati nello script completo.
