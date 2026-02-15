-- ==================================================================================
-- SCRIPT UNICO DI INIZIALIZZAZIONE DATABASE (parametrizzato per tenant)
-- ==================================================================================
-- Consolida: Script_DB_Initialization, staging_tables.sql, utenti, permessi,
-- stored procedure, indici di performance, tabelle di appoggio.
--
-- Eseguire con utente amministratore (es. root).
--
-- OPZIONE A - Generare SQL con valori sostituiti (consigliato):
--   .\sql\initialization\generate-db-init.ps1 -Tenant amex -AppPassword "xxx" -StgAdminPassword "yyy" -StgPassword "zzz"
--   Poi eseguire il file generato (es. Script_DB_Complete_Initialization_amex.sql) dal client MySQL.
--
-- OPZIONE B - Sostituire manualmente in questo file i placeholder sotto, poi eseguire:
--   __DB_NAME__       es. amex_posappdb
--   __APP_USER__      es. amex_posappusr
--   __APP_PASSWORD__  password utente app (escape ' con '')
--   __STG_ADMIN_USER__   es. amex_stg_admin
--   __STG_ADMIN_PASSWORD__
--   __STG_USER__      es. amex_posappusr_stg
--   __STG_PASSWORD__
--
-- I nomi delle TABELLE restano invariati (STG_TRANSACTION, MERCHANT, ecc.).
-- ==================================================================================

-- ==================================================================================
-- 1. DATABASE E UTENTE PRINCIPALE (sostituire i placeholder con i valori del tenant)
-- ==================================================================================
CREATE DATABASE IF NOT EXISTS `__DB_NAME__`;
CREATE USER IF NOT EXISTS '__APP_USER__'@'%' IDENTIFIED BY '__APP_PASSWORD__';

GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE, CREATE TEMPORARY TABLES ON `__DB_NAME__`.* TO '__APP_USER__'@'%';
GRANT LOCK TABLES ON `__DB_NAME__`.* TO '__APP_USER__'@'%';

USE `__DB_NAME__`;

-- ==================================================================================
-- 2. TABELLE CORE (Schema principale)
-- ==================================================================================

CREATE TABLE `SPRING_SESSION` (
  `PRIMARY_ID` char(36) NOT NULL,
  `SESSION_ID` char(36) NOT NULL,
  `CREATION_TIME` bigint NOT NULL,
  `LAST_ACCESS_TIME` bigint NOT NULL,
  `MAX_INACTIVE_INTERVAL` int NOT NULL,
  `EXPIRY_TIME` bigint NOT NULL,
  `PRINCIPAL_NAME` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`PRIMARY_ID`),
  UNIQUE KEY `SPRING_SESSION_IX1` (`SESSION_ID`),
  KEY `SPRING_SESSION_IX2` (`EXPIRY_TIME`),
  KEY `SPRING_SESSION_IX3` (`PRINCIPAL_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `SPRING_SESSION_ATTRIBUTES` (
  `SESSION_PRIMARY_ID` char(36) NOT NULL,
  `ATTRIBUTE_NAME` varchar(200) NOT NULL,
  `ATTRIBUTE_BYTES` blob NOT NULL,
  PRIMARY KEY (`SESSION_PRIMARY_ID`,`ATTRIBUTE_NAME`),
  CONSTRAINT `SPRING_SESSION_ATTRIBUTES_FK` FOREIGN KEY (`SESSION_PRIMARY_ID`) REFERENCES `SPRING_SESSION` (`PRIMARY_ID`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `USER` (
  `pk_user` VARCHAR(50) PRIMARY KEY,
  `first_name` VARCHAR(255),
  `last_name` VARCHAR(255),
  `email` VARCHAR(255),
  `company` VARCHAR(255),
  `office` VARCHAR(255),
  `last_logged_in` DATETIME
) ENGINE=InnoDB;

CREATE TABLE `AUTHORITY` (
  `pk_authority` VARCHAR(50) PRIMARY KEY,
  `description` VARCHAR(255)
) ENGINE=InnoDB;

CREATE TABLE `USER_AUTHORITY` (
  `fk_user` VARCHAR(50),
  `fk_authority` VARCHAR(50),
  PRIMARY KEY (`fk_user`, `fk_authority`),
  FOREIGN KEY (`fk_user`) REFERENCES `USER`(`pk_user`),
  FOREIGN KEY (`fk_authority`) REFERENCES `AUTHORITY`(`pk_authority`)
) ENGINE=InnoDB;

CREATE TABLE `PERIOD` (
  `pk_period` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(50),
  `description` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `OBBLIGATION` (
  `pk_obbligation` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_period` BIGINT,
  `fiscalYear` INT,
  FOREIGN KEY (`fk_period`) REFERENCES `PERIOD`(`pk_period`)
) ENGINE=InnoDB;

CREATE TABLE `SUBMISSION_STATUS` (
  `pk_submission_status` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `code` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `SUBMISSION_TYPE` (
  `pk_submission_type` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `SUBMISSION` (
  `pk_submission` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_current_submission_status` BIGINT,
  `fk_last_update_by` VARCHAR(50),
  `fk_obbligation` BIGINT,
  `fk_submission_type` BIGINT,
  `last_updated_at` DATETIME,
  `approved_at` DATETIME,
  `deadline_date` DATE,
  `batch_id` VARCHAR(100),
  `is_manual` BOOLEAN,
  `fk_lastStatusBeforeCancel` BIGINT,
  `cancelled_at` DATETIME,
  FOREIGN KEY (`fk_current_submission_status`) REFERENCES `SUBMISSION_STATUS`(`pk_submission_status`),
  FOREIGN KEY (`fk_last_update_by`) REFERENCES `USER`(`pk_user`),
  FOREIGN KEY (`fk_submission_type`) REFERENCES `SUBMISSION_TYPE`(`pk_submission_type`),
  FOREIGN KEY (`fk_obbligation`) REFERENCES `OBBLIGATION`(`pk_obbligation`),
  FOREIGN KEY (`fk_lastStatusBeforeCancel`) REFERENCES `SUBMISSION_STATUS`(`pk_submission_status`)
) ENGINE=InnoDB;

CREATE TABLE `INGESTION_TYPE` (
  `pk_ingestion_type` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `INGESTION_STATUS` (
  `pk_ingestion_status` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `INGESTION_ERROR` (
  `pk_ingestion_error` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `order` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `INGESTION` (
  `pk_ingestion` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_ingestion_type` BIGINT,
  `fk_ingestion_status` BIGINT,
  `fk_submission` BIGINT,
  `fk_ingestion_error` BIGINT,
  `ingested_at` DATETIME,
  `full_path` VARCHAR(255) COMMENT 'Full path on bucket S3 or remote folder where this file originated from',
  FOREIGN KEY (`fk_ingestion_type`) REFERENCES `INGESTION_TYPE`(`pk_ingestion_type`),
  FOREIGN KEY (`fk_ingestion_status`) REFERENCES `INGESTION_STATUS`(`pk_ingestion_status`),
  FOREIGN KEY (`fk_submission`) REFERENCES `SUBMISSION`(`pk_submission`),
  FOREIGN KEY (`fk_ingestion_error`) REFERENCES `INGESTION_ERROR`(`pk_ingestion_error`)
) ENGINE=InnoDB;

CREATE TABLE `OUTPUT_FILE` (
  `pk_output` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_submission` BIGINT,
  `full_path` VARCHAR(255) COMMENT 'Full path on bucket S3 or remote folder where this file is uploaded',
  `extention_type` VARCHAR(255),
  `generated_at` VARCHAR(255),
  FOREIGN KEY (`fk_submission`) REFERENCES `SUBMISSION`(`pk_submission`)
) ENGINE=InnoDB;

CREATE TABLE `ERROR_TYPE` (
  `pk_error_type` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(255),
  `description` VARCHAR(255),
  `serverity_level` INT,
  `is_active` BOOLEAN
) ENGINE=InnoDB;

CREATE TABLE `LOG` (
  `pk_log` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_sbmission` BIGINT,
  `fk_updater` VARCHAR(50),
  `fk_before_sumbission_status` BIGINT,
  `fk_after_sumbission_status` BIGINT,
  `timestamp` datetime,
  `message` VARCHAR(255),
  FOREIGN KEY (`fk_sbmission`) REFERENCES `SUBMISSION`(`pk_submission`),
  FOREIGN KEY (`fk_updater`) REFERENCES `USER`(`pk_user`),
  FOREIGN KEY (`fk_before_sumbission_status`) REFERENCES `SUBMISSION_STATUS`(`pk_submission_status`),
  FOREIGN KEY (`fk_after_sumbission_status`) REFERENCES `SUBMISSION_STATUS`(`pk_submission_status`)
) ENGINE=InnoDB;

CREATE TABLE `MERCHANT` (
  `pk_merchant` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_ingestion` BIGINT,
  `tp_rec` VARCHAR(1),
  `id_intermediario` VARCHAR(11),
  `id_esercente` VARCHAR(30),
  `cod_fiscale` VARCHAR(16),
  `partita_iva` VARCHAR(11),
  UNIQUE KEY unique_esercente_intermediario (`id_esercente`, `id_intermediario`)
) ENGINE=InnoDB;

-- MERCHANT_ACCOUNT rimossa: non usata da codice (nessun service/repository la utilizza).

CREATE TABLE `TRANSACTION` (
  `pk_transaction` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_ingestion` BIGINT,
  `tp_rec` VARCHAR(1),
  `id_intermediario` varchar(11),
  `id_esercente` varchar(30),
  `chiave_banca` varchar(50),
  `id_pos` varchar(30),
  `tipo_ope` varchar(2),
  `dt_ope` VARCHAR(8) NULL,
  `divisa_ope` varchar(3),
  `tipo_pag` varchar(2),
  `imp_ope` decimal(14,2),
  `tot_ope` INT,
  FOREIGN KEY (`fk_ingestion`) REFERENCES `INGESTION`(`pk_ingestion`),
  UNIQUE KEY transaction_unique (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag),
  CONSTRAINT fk_esercente_intermediario FOREIGN KEY (`id_esercente`, `id_intermediario`) REFERENCES MERCHANT(`id_esercente`, `id_intermediario`)
) ENGINE=InnoDB;

ALTER TABLE TRANSACTION
  ADD COLUMN fk_output BIGINT NULL,
  ADD CONSTRAINT OUTPUT_FK FOREIGN KEY (fk_output) REFERENCES OUTPUT_FILE(pk_output);

ALTER TABLE `TRANSACTION` ADD COLUMN created_at DATETIME NULL;

CREATE TABLE `ERROR_RECORD` (
  `pk_error_record` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_ingestion` BIGINT,
  `fk_error_type` BIGINT NOT NULL,
  `error_message` VARCHAR(2500),
  `raw_row` VARCHAR(250),
  FOREIGN KEY (`fk_ingestion`) REFERENCES `INGESTION`(`pk_ingestion`),
  FOREIGN KEY (fk_error_type) REFERENCES `ERROR_TYPE`(pk_error_type)
) ENGINE=InnoDB;

CREATE TABLE MONTHLY_OUTPUT (
  pk_monthly_output BIGINT AUTO_INCREMENT PRIMARY KEY,
  tp_rec VARCHAR(1),
  cf_sogg_obbligato VARCHAR(16),
  tipo_ope VARCHAR(2),
  dt_ope INT,
  tot_ope INT,
  imp_ope DECIMAL(14,2),
  divisa_ope VARCHAR(3),
  id_esercente VARCHAR(30),
  id_pos VARCHAR(30),
  tipo_pag VARCHAR(2),
  cod_fiscale VARCHAR(16),
  partita_iva VARCHAR(11),
  chiave_banca VARCHAR(50)
) ENGINE=InnoDB;

CREATE TABLE CONSISTENCY_OUTPUT (
  pk_consistency_output BIGINT AUTO_INCREMENT PRIMARY KEY,
  tp_rec VARCHAR(1),
  cf_sogg_obbligato VARCHAR(16) COMMENT 'Codice Fiscale del soggetto obbligato alla comunicazione - Acquirer',
  tipo_ope VARCHAR(2),
  mese_ope INT,
  anno_ope INT,
  imp_ope DECIMAL(14,2),
  tot_ope INT,
  id_esercente VARCHAR(30),
  id_pos VARCHAR(30),
  tipo_pag VARCHAR(2),
  cod_fiscale VARCHAR(16),
  partita_iva VARCHAR(11),
  chiave_banca VARCHAR(50)
) ENGINE=InnoDB;

CREATE TABLE SUBMISSION_STATUS_GROUP (
  pk_submission_status_group bigint not null auto_increment,
  name varchar(255),
  description varchar(255),
  code varchar(255),
  `order` integer,
  is_active bit,
  primary key (pk_submission_status_group)
) ENGINE=InnoDB;

ALTER TABLE SUBMISSION_STATUS ADD COLUMN fk_submission_status_group BIGINT;
ALTER TABLE SUBMISSION_STATUS ADD CONSTRAINT SUBMISSION_STATUS_GROUP_FK FOREIGN KEY (fk_submission_status_group) REFERENCES SUBMISSION_STATUS_GROUP (pk_submission_status_group);

ALTER TABLE SUBMISSION ADD COLUMN fk_submission_status BIGINT;
ALTER TABLE SUBMISSION ADD CONSTRAINT SUBMISSION_STATUS_FK FOREIGN KEY (fk_submission_status) REFERENCES SUBMISSION_STATUS (pk_submission_status);

-- cancelled_at già presente in CREATE TABLE SUBMISSION (non aggiungere di nuovo)
ALTER TABLE ERROR_TYPE ADD COLUMN error_code varchar(255) NULL;
ALTER TABLE ERROR_RECORD ADD COLUMN created_at DATETIME NULL;
ALTER TABLE MERCHANT ADD COLUMN created_at DATETIME NULL;
ALTER TABLE MERCHANT ADD COLUMN id_salmov VARCHAR(50) NULL;
ALTER TABLE TRANSACTION MODIFY COLUMN dt_ope VARCHAR(8) NULL;

ALTER TABLE MERCHANT ADD COLUMN fk_submission BIGINT NULL, ADD CONSTRAINT MERCHANT_SUBMISSION_FK FOREIGN KEY (fk_submission) REFERENCES SUBMISSION(pk_submission) ON DELETE RESTRICT;
UPDATE MERCHANT m JOIN INGESTION i ON m.fk_ingestion = i.pk_ingestion SET m.fk_submission = i.fk_submission;

ALTER TABLE TRANSACTION ADD COLUMN fk_submission BIGINT NULL, ADD CONSTRAINT TRANSACTION_SUBMISSION_FK FOREIGN KEY (fk_submission) REFERENCES SUBMISSION(pk_submission) ON DELETE RESTRICT;
UPDATE TRANSACTION t JOIN INGESTION i ON t.fk_ingestion = i.pk_ingestion SET t.fk_submission = i.fk_submission;

ALTER TABLE MERCHANT ADD UNIQUE KEY `unique_esercente_intermediario_submission` (`id_esercente`, `id_intermediario`, `fk_submission`);
ALTER TABLE TRANSACTION ADD CONSTRAINT `fk_esercente_intermediario_submission` FOREIGN KEY (`id_esercente`, `id_intermediario`, `fk_submission`) REFERENCES MERCHANT (`id_esercente`, `id_intermediario`, `fk_submission`);
ALTER TABLE TRANSACTION DROP FOREIGN KEY fk_esercente_intermediario;
ALTER TABLE MERCHANT DROP CONSTRAINT unique_esercente_intermediario;
ALTER TABLE MERCHANT MODIFY COLUMN fk_submission BIGINT NOT NULL;
ALTER TABLE TRANSACTION MODIFY COLUMN fk_submission BIGINT NOT NULL;

-- Unique key TRANSACTION deve includere fk_submission (stessa transazione può esistere in submission diverse)
ALTER TABLE TRANSACTION DROP INDEX transaction_unique;
ALTER TABLE TRANSACTION ADD UNIQUE KEY transaction_unique (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, fk_submission);

CREATE TABLE `ERROR_CAUSE` (
  `pk_error_cause` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_error_record` BIGINT,
  `fk_error_type` BIGINT NOT NULL,
  `error_message` VARCHAR(2500),
  FOREIGN KEY (fk_error_record) REFERENCES `ERROR_RECORD`(`pk_error_record`),
  FOREIGN KEY (fk_error_type) REFERENCES `ERROR_TYPE`(pk_error_type)
) ENGINE=InnoDB;

ALTER TABLE ERROR_RECORD DROP FOREIGN KEY ERROR_RECORD_ibfk_2, DROP COLUMN error_message, DROP COLUMN fk_error_type;
ALTER TABLE ERROR_RECORD ADD COLUMN fk_submission BIGINT NULL;
UPDATE ERROR_RECORD er JOIN INGESTION i ON er.fk_ingestion = i.pk_ingestion SET er.fk_submission = i.fk_submission;
ALTER TABLE ERROR_RECORD MODIFY COLUMN fk_submission BIGINT NOT NULL;
ALTER TABLE ERROR_CAUSE ADD COLUMN fk_submission BIGINT NULL;
UPDATE ERROR_CAUSE ec JOIN ERROR_RECORD er ON ec.fk_error_record = er.pk_error_record JOIN INGESTION i ON er.fk_ingestion = i.pk_ingestion SET ec.fk_submission = i.fk_submission;
ALTER TABLE ERROR_CAUSE MODIFY COLUMN fk_submission BIGINT NOT NULL;

CREATE TABLE `RESOLVED_TRANSACTION` (
  `pk_resolved_transaction` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `fk_ingestion` BIGINT,
  `fk_submission` BIGINT,
  `tp_rec` VARCHAR(1),
  `id_intermediario` varchar(11),
  `id_esercente` varchar(30),
  `chiave_banca` varchar(50),
  `id_pos` varchar(30),
  `tipo_ope` varchar(2),
  `dt_ope` VARCHAR(8) NULL,
  `divisa_ope` varchar(3),
  `tipo_pag` varchar(2),
  `imp_ope` decimal(14,2),
  `tot_ope` INT,
  FOREIGN KEY (`fk_ingestion`) REFERENCES `INGESTION`(`pk_ingestion`),
  FOREIGN KEY (`fk_submission`) REFERENCES `SUBMISSION`(`pk_submission`),
  UNIQUE KEY resolved_transaction_unique (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag)
) ENGINE=InnoDB;

ALTER TABLE `RESOLVED_TRANSACTION` ADD COLUMN created_at DATETIME NULL;
-- Allineamento dt_ope a VARCHAR(8) (entity usa String); utile se la tabella era stata creata con dt_ope INT
ALTER TABLE `RESOLVED_TRANSACTION` MODIFY COLUMN dt_ope VARCHAR(8) NULL;
ALTER TABLE TRANSACTION MODIFY COLUMN dt_ope VARCHAR(8) NULL;

ALTER TABLE `RESOLVED_TRANSACTION` ADD COLUMN fk_output BIGINT NULL, ADD CONSTRAINT OUTPUT_RESOLVED_FK FOREIGN KEY (fk_output) REFERENCES OUTPUT_FILE(pk_output);
ALTER TABLE RESOLVED_TRANSACTION ADD COLUMN fk_current_submission BIGINT NULL, ADD CONSTRAINT CURRENT_SUBMISSION_FK FOREIGN KEY (fk_current_submission) REFERENCES SUBMISSION(pk_submission);

-- ==================================================================================
-- 3. TABELLE DI STAGING (ETL)
-- ==================================================================================

DROP TABLE IF EXISTS STG_TRANSACTION;
DROP TABLE IF EXISTS STG_MERCHANT;

CREATE TABLE STG_MERCHANT (
  pk_stg_merchant BIGINT AUTO_INCREMENT PRIMARY KEY,
  id_esercente VARCHAR(30) NOT NULL,
  id_intermediario VARCHAR(11) NOT NULL,
  tp_rec VARCHAR(1),
  cod_fiscale VARCHAR(16),
  partita_iva VARCHAR(11),
  id_salmov VARCHAR(50),
  fk_ingestion BIGINT NOT NULL,
  fk_submission BIGINT NOT NULL,
  raw_row VARCHAR(250),
  process_status TINYINT DEFAULT NULL,
  error_message VARCHAR(255),
  loaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_stg_merchant_keys (id_esercente, id_intermediario, fk_submission),
  INDEX idx_stg_merchant_status (process_status),
  INDEX idx_stg_merchant_submission (fk_submission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE STG_TRANSACTION (
  pk_stg_transaction BIGINT AUTO_INCREMENT PRIMARY KEY,
  id_esercente VARCHAR(30) NOT NULL,
  chiave_banca VARCHAR(50),
  id_pos VARCHAR(30),
  tipo_ope VARCHAR(2),
  dt_ope VARCHAR(10),
  divisa_ope VARCHAR(3),
  tp_rec VARCHAR(1),
  id_intermediario VARCHAR(11),
  tipo_pag VARCHAR(2),
  imp_ope DECIMAL(14,2),
  tot_ope INT,
  fk_ingestion BIGINT NOT NULL,
  fk_submission BIGINT NOT NULL,
  raw_row VARCHAR(250),
  process_status TINYINT DEFAULT NULL,
  error_message VARCHAR(255),
  loaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_stg_trx_keys (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag),
  INDEX idx_stg_trx_merchant (id_esercente, id_intermediario),
  INDEX idx_stg_trx_status (process_status),
  INDEX idx_stg_trx_submission (fk_submission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==================================================================================
-- 4. TABELLA TEMP INSERTED TRANSACTIONS (tracking chunk)
-- ==================================================================================

CREATE TABLE IF NOT EXISTS temp_inserted_transactions (
  id_esercente VARCHAR(30) NOT NULL,
  chiave_banca VARCHAR(50),
  id_pos VARCHAR(30),
  tipo_ope VARCHAR(2),
  dt_ope VARCHAR(10),
  divisa_ope VARCHAR(3),
  tipo_pag VARCHAR(2),
  PRIMARY KEY (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag)
) ENGINE=InnoDB;

-- ==================================================================================
-- 5. TABELLE DI APPOGGIO OUTPUT (MEMORY)
-- ==================================================================================

CREATE TABLE IF NOT EXISTS TEMP_OUTPUT_TRANSACTION_IDS (
  pk_transaction BIGINT PRIMARY KEY,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_created_at (created_at)
) ENGINE=MEMORY COMMENT='Tabella di appoggio per batch update di fk_output su TRANSACTION';

CREATE TABLE IF NOT EXISTS TEMP_OUTPUT_RESOLVED_TRANSACTION_IDS (
  pk_resolved_transaction BIGINT PRIMARY KEY,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_created_at (created_at)
) ENGINE=MEMORY COMMENT='Tabella di appoggio per batch update di fk_output su RESOLVED_TRANSACTION';

-- ==================================================================================
-- 6. UTENTE STG_ADMIN (DEFINER per sp_clear_staging) – placeholder: __STG_ADMIN_USER__
-- ==================================================================================

CREATE USER IF NOT EXISTS '__STG_ADMIN_USER__'@'%' IDENTIFIED BY '__STG_ADMIN_PASSWORD__';
GRANT DROP ON `__DB_NAME__`.STG_TRANSACTION TO '__STG_ADMIN_USER__'@'%';
GRANT DROP ON `__DB_NAME__`.STG_MERCHANT TO '__STG_ADMIN_USER__'@'%';
GRANT DROP ON `__DB_NAME__`.temp_inserted_transactions TO '__STG_ADMIN_USER__'@'%';

-- ==================================================================================
-- 7. UTENTE POSAPPUSR_STG (opzionale: per gestione procedure) – placeholder: __STG_USER__
-- ==================================================================================

CREATE USER IF NOT EXISTS '__STG_USER__'@'%' IDENTIFIED BY '__STG_PASSWORD__';
GRANT USAGE ON `__DB_NAME__`.* TO '__STG_USER__'@'%';
GRANT CREATE ROUTINE ON `__DB_NAME__`.* TO '__STG_USER__'@'%';
GRANT DROP ON `__DB_NAME__`.STG_TRANSACTION TO '__STG_USER__'@'%';
GRANT DROP ON `__DB_NAME__`.STG_MERCHANT TO '__STG_USER__'@'%';
GRANT DROP ON `__DB_NAME__`.temp_inserted_transactions TO '__STG_USER__'@'%';

FLUSH PRIVILEGES;

-- ==================================================================================
-- 8. STORED PROCEDURE: sp_clear_staging (DEFINER = <tenant>_stg_admin)
-- ==================================================================================

DELIMITER //
DROP PROCEDURE IF EXISTS sp_clear_staging //
CREATE PROCEDURE sp_clear_staging()
SQL SECURITY DEFINER
BEGIN
  TRUNCATE TABLE STG_TRANSACTION;
  TRUNCATE TABLE STG_MERCHANT;
  TRUNCATE TABLE temp_inserted_transactions;
END //
DELIMITER ;

-- ==================================================================================
-- 9. STORED PROCEDURE: sp_insert_merchants_from_staging
-- ==================================================================================

DELIMITER //
DROP PROCEDURE IF EXISTS sp_insert_merchants_from_staging //
CREATE PROCEDURE sp_insert_merchants_from_staging(
  IN p_submission_id BIGINT,
  OUT p_inserted_count INT,
  OUT p_duplicate_count INT
)
BEGIN
  UPDATE STG_MERCHANT stg
  INNER JOIN MERCHANT m ON stg.id_esercente = m.id_esercente AND stg.id_intermediario = m.id_intermediario AND stg.fk_submission = m.fk_submission
  SET stg.process_status = 2, stg.error_message = 'Duplicate: merchant already exists'
  WHERE stg.fk_submission = p_submission_id AND stg.process_status IS NULL;
  SET p_duplicate_count = ROW_COUNT();

  UPDATE STG_MERCHANT stg
  INNER JOIN (
    SELECT id_esercente, id_intermediario, MIN(pk_stg_merchant) AS first_pk
    FROM STG_MERCHANT
    WHERE fk_submission = p_submission_id AND process_status IS NULL
    GROUP BY id_esercente, id_intermediario
    HAVING COUNT(*) > 1
  ) dups ON stg.id_esercente = dups.id_esercente AND stg.id_intermediario = dups.id_intermediario AND stg.pk_stg_merchant > dups.first_pk
  SET stg.process_status = 2, stg.error_message = 'Duplicate within batch'
  WHERE stg.fk_submission = p_submission_id AND stg.process_status IS NULL;
  SET p_duplicate_count = p_duplicate_count + ROW_COUNT();

  INSERT INTO MERCHANT (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, cod_fiscale, partita_iva, id_salmov, created_at)
  SELECT fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, cod_fiscale, partita_iva, id_salmov, CURRENT_TIMESTAMP
  FROM STG_MERCHANT
  WHERE fk_submission = p_submission_id AND process_status IS NULL;
  SET p_inserted_count = ROW_COUNT();

  UPDATE STG_MERCHANT SET process_status = 1 WHERE fk_submission = p_submission_id AND process_status IS NULL;
END //
DELIMITER ;

-- ==================================================================================
-- 10. STORED PROCEDURE: sp_insert_transactions_from_staging
-- ==================================================================================

DELIMITER //
DROP PROCEDURE IF EXISTS sp_insert_transactions_from_staging //
CREATE PROCEDURE sp_insert_transactions_from_staging(
  IN p_submission_id BIGINT,
  OUT p_inserted_count INT,
  OUT p_duplicate_count INT,
  OUT p_missing_merchant_count INT
)
BEGIN
  UPDATE STG_TRANSACTION stg
  LEFT JOIN MERCHANT m ON stg.id_esercente = m.id_esercente AND stg.id_intermediario = m.id_intermediario
  SET stg.process_status = 3, stg.error_message = CONCAT('Merchant not found: ', stg.id_esercente, '/', stg.id_intermediario)
  WHERE stg.fk_submission = p_submission_id AND stg.process_status IS NULL AND m.pk_merchant IS NULL;
  SET p_missing_merchant_count = ROW_COUNT();

  UPDATE STG_TRANSACTION stg
  INNER JOIN TRANSACTION t ON stg.id_esercente = t.id_esercente AND stg.chiave_banca = t.chiave_banca AND stg.id_pos = t.id_pos AND stg.tipo_ope = t.tipo_ope AND stg.dt_ope = t.dt_ope AND stg.divisa_ope = t.divisa_ope AND stg.tipo_pag = t.tipo_pag
  SET stg.process_status = 2, stg.error_message = 'Duplicate: transaction already exists'
  WHERE stg.fk_submission = p_submission_id AND stg.process_status IS NULL;
  SET p_duplicate_count = ROW_COUNT();

  UPDATE STG_TRANSACTION stg
  INNER JOIN (
    SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, MIN(pk_stg_transaction) AS first_pk
    FROM STG_TRANSACTION
    WHERE fk_submission = p_submission_id AND process_status IS NULL
    GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
    HAVING COUNT(*) > 1
  ) dups ON stg.id_esercente = dups.id_esercente AND stg.chiave_banca = dups.chiave_banca AND stg.id_pos = dups.id_pos AND stg.tipo_ope = dups.tipo_ope AND stg.dt_ope = dups.dt_ope AND stg.divisa_ope = dups.divisa_ope AND stg.tipo_pag = dups.tipo_pag AND stg.pk_stg_transaction > dups.first_pk
  SET stg.process_status = 2, stg.error_message = 'Duplicate within batch'
  WHERE stg.fk_submission = p_submission_id AND stg.process_status IS NULL;
  SET p_duplicate_count = p_duplicate_count + ROW_COUNT();

  INSERT INTO TRANSACTION (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, imp_ope, tot_ope, created_at)
  SELECT fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, imp_ope, tot_ope, CURRENT_TIMESTAMP
  FROM STG_TRANSACTION
  WHERE fk_submission = p_submission_id AND process_status IS NULL;
  SET p_inserted_count = ROW_COUNT();

  UPDATE STG_TRANSACTION SET process_status = 1 WHERE fk_submission = p_submission_id AND process_status IS NULL;
END //
DELIMITER ;

-- ==================================================================================
-- 11. GRANT EXECUTE a __APP_USER__
-- ==================================================================================

GRANT EXECUTE ON PROCEDURE `__DB_NAME__`.sp_clear_staging TO '__APP_USER__'@'%';
GRANT SELECT, INSERT, DELETE ON `__DB_NAME__`.temp_inserted_transactions TO '__APP_USER__'@'%';
FLUSH PRIVILEGES;

-- ==================================================================================
-- 12. INDICI DI PERFORMANCE (con procedura if-not-exists)
-- ==================================================================================

DELIMITER $$
DROP PROCEDURE IF EXISTS create_index_if_not_exists$$
CREATE PROCEDURE create_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_columns VARCHAR(512)
)
BEGIN
  DECLARE index_exists INT DEFAULT 0;
  SELECT COUNT(*) INTO index_exists
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = p_table_name AND index_name = p_index_name;
  IF index_exists = 0 THEN
    SET @sql = CONCAT('CREATE INDEX ', p_index_name, ' ON ', p_table_name, ' (', p_index_columns, ')');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- Core / ingestion
CALL create_index_if_not_exists('MERCHANT', 'idx_merchant_esercente_intermediario', 'id_esercente, id_intermediario');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_esercente_intermediario', 'id_esercente, id_intermediario');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_submission', 'fk_submission');
CALL create_index_if_not_exists('MERCHANT', 'idx_merchant_submission', 'fk_submission');
CALL create_index_if_not_exists('MERCHANT', 'idx_merchant_ingestion', 'fk_ingestion');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_ingestion', 'fk_ingestion');
CALL create_index_if_not_exists('RESOLVED_TRANSACTION', 'idx_resolved_transaction_lookup', 'id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope');

-- Staging / batch
CALL create_index_if_not_exists('STG_TRANSACTION', 'idx_stg_trx_submission_status', 'fk_submission, process_status');
CALL create_index_if_not_exists('STG_TRANSACTION', 'idx_stg_trx_submission_status_pk', 'fk_submission, process_status, pk_stg_transaction');
CALL create_index_if_not_exists('STG_TRANSACTION', 'idx_stg_trx_sub_status_pk_merchant', 'fk_submission, process_status, pk_stg_transaction, id_esercente, id_intermediario');
CALL create_index_if_not_exists('MERCHANT', 'idx_merchant_esercente_intermediario_submission', 'id_esercente, id_intermediario, fk_submission');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_duplicate_check', 'id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope');

-- Validation / error
CALL create_index_if_not_exists('ERROR_CAUSE', 'idx_error_cause_error_record', 'fk_error_record');
CALL create_index_if_not_exists('ERROR_CAUSE', 'idx_error_cause_error_type', 'fk_error_type');
CALL create_index_if_not_exists('ERROR_CAUSE', 'idx_error_cause_submission', 'fk_submission');
CALL create_index_if_not_exists('ERROR_CAUSE', 'idx_error_cause_record_type', 'fk_error_record, fk_error_type');
CALL create_index_if_not_exists('ERROR_TYPE', 'idx_error_type_severity_pk', 'serverity_level, pk_error_type');
CALL create_index_if_not_exists('ERROR_RECORD', 'idx_error_record_ingestion', 'fk_ingestion');
CALL create_index_if_not_exists('ERROR_RECORD', 'idx_error_record_submission', 'fk_submission');
CALL create_index_if_not_exists('ERROR_TYPE', 'idx_error_type_severity_level', 'serverity_level');
CALL create_index_if_not_exists('ERROR_TYPE', 'idx_error_type_error_code', 'error_code');
CALL create_index_if_not_exists('INGESTION', 'idx_ingestion_submission', 'fk_submission');
CALL create_index_if_not_exists('ERROR_RECORD', 'idx_error_record_ingestion_raw_row', 'fk_ingestion, raw_row');

-- Output
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_fk_output_pk', 'fk_output, pk_transaction');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_fk_submission_fk_output', 'fk_submission, fk_output');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_submission_output', 'fk_submission, fk_output, pk_transaction');
CALL create_index_if_not_exists('TRANSACTION', 'idx_transaction_merchant_keys', 'id_esercente, id_intermediario');
CALL create_index_if_not_exists('RESOLVED_TRANSACTION', 'idx_resolved_transaction_fk_output_pk', 'fk_output, pk_resolved_transaction');
CALL create_index_if_not_exists('RESOLVED_TRANSACTION', 'idx_resolved_transaction_fk_current_submission_fk_output', 'fk_current_submission, fk_output');
CALL create_index_if_not_exists('RESOLVED_TRANSACTION', 'idx_resolved_transaction_current_submission_output', 'fk_current_submission, fk_output, pk_resolved_transaction');
CALL create_index_if_not_exists('RESOLVED_TRANSACTION', 'idx_resolved_transaction_merchant_keys', 'id_esercente, id_intermediario');

DROP PROCEDURE IF EXISTS create_index_if_not_exists;

-- ==================================================================================
-- 12b. DATI STATICI (lookup: PERIOD, ERROR_TYPE, INGESTION_STATUS, INGESTION_TYPE,
--     SUBMISSION_STATUS_GROUP, SUBMISSION_STATUS, SUBMISSION_TYPE)
--     Necessari per il batch (es. SubmissionStatus order 1 = INGESTION_FINISHED,
--     Period by name per obligation: January, February, ...).
-- ==================================================================================

-- PERIOD (12 mesi: name = Month.getDisplayName(FULL, ENGLISH), order = 1..12)
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (1, 'January', 'Month 1', 1, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (2, 'February', 'Month 2', 2, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (3, 'March', 'Month 3', 3, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (4, 'April', 'Month 4', 4, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (5, 'May', 'Month 5', 5, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (6, 'June', 'Month 6', 6, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (7, 'July', 'Month 7', 7, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (8, 'August', 'Month 8', 8, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (9, 'September', 'Month 9', 9, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (10, 'October', 'Month 10', 10, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (11, 'November', 'Month 11', 11, 1);
INSERT INTO PERIOD (pk_period, name, description, `order`, is_active) VALUES (12, 'December', 'Month 12', 12, 1);

-- ERROR_TYPE
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (1, 'Invalid format', 'Invalid format', 1, 1, 'WRN1');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (2, 'Invalid date format', 'Invalid date format', 1, 1, 'WRN2');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (3, 'Mandatory data is missing', 'Mandatory data is missing', 1, 1, 'WRN3');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (4, 'Invalid value', 'Invalid value', 1, 1, 'WRN4');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (5, 'Foreign Key issue', 'Transaction can''t be created before its merchant and merchant acocount', 2, 1, 'ERR1');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (6, 'Transaction already exists', 'Transaction can''t be created as it is already existing based on uniqu constrain', 2, 1, 'ERR2');
INSERT INTO ERROR_TYPE (pk_error_type, name, description, serverity_level, is_active, error_code) VALUES (7, 'Merchant already exists', 'Merchant can''t be created as it is already existing based on uniqu constrain', 2, 1, 'ERR3');

-- INGESTION_STATUS
INSERT INTO INGESTION_STATUS (pk_ingestion_status, name, description, `order`, is_active) VALUES (1, 'Failed', 'mock desc', 1, 1);
INSERT INTO INGESTION_STATUS (pk_ingestion_status, name, description, `order`, is_active) VALUES (2, 'Processing', 'mock desc', 2, 1);
INSERT INTO INGESTION_STATUS (pk_ingestion_status, name, description, `order`, is_active) VALUES (3, 'Success', 'mock desc', 3, 1);

-- INGESTION_TYPE
INSERT INTO INGESTION_TYPE (pk_ingestion_type, name, description, `order`, is_active) VALUES (1, 'anagrafe', 'mock desc', 1, 1);
INSERT INTO INGESTION_TYPE (pk_ingestion_type, name, description, `order`, is_active) VALUES (2, 'transato', 'mock desc', 2, 1);

-- SUBMISSION_STATUS_GROUP
INSERT INTO SUBMISSION_STATUS_GROUP (pk_submission_status_group, name, description, code, `order`, is_active) VALUES (1, 'Ingestion', 'mock desc', '1', 1, 1);
INSERT INTO SUBMISSION_STATUS_GROUP (pk_submission_status_group, name, description, code, `order`, is_active) VALUES (2, 'Proccessing', 'mock desc', '2', 2, 1);
INSERT INTO SUBMISSION_STATUS_GROUP (pk_submission_status_group, name, description, code, `order`, is_active) VALUES (3, 'Submission', 'mock desc', '3', 3, 1);

-- SUBMISSION_STATUS (order 1 = INGESTION_FINISHED, richiesto dal batch)
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (1, 'INGESTION FINISHED', 'mock description', 'ING_OVER', 1, 1, 1);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (2, 'DATA VALIDATION', 'mock description', 'ING_OVER', 2, 1, 1);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (3, 'VALIDATION COMPLETED', 'mock description', 'ING_OVER', 3, 1, 1);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (4, 'NEXI''S APPROVAL', 'mock description', 'ING_OVER', 4, 1, 1);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (5, 'PROCESSING', 'mock description', 'ING_OVER', 5, 1, 2);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (6, 'DELOITTE REVIEW', 'mock description', 'ING_OVER', 6, 1, 2);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (7, 'CLIENT REVIEW', 'mock description', 'ING_OVER', 7, 1, 2);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (8, 'PENDING SUBMISSION', 'mock description', 'ING_OVER', 8, 1, 3);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (9, 'SUBMITTED', 'mock description', 'ING_OVER', 9, 1, 3);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (10, 'COMPLETED', 'mock description', 'ING_OVER', 10, 1, 3);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (11, 'CANCELLED', 'mock description', 'ING_OVER', 11, 1, 3);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (12, 'REJECTED', 'mock description', 'ING_OVER', 12, 1, 3);
INSERT INTO SUBMISSION_STATUS (pk_submission_status, name, description, code, `order`, is_active, fk_submission_status_group) VALUES (13, 'ERROR', 'Submission in error (e.g. ingestion failure)', 'ING_OVER', 13, 1, 3);

-- SUBMISSION_TYPE
INSERT INTO SUBMISSION_TYPE (pk_submission_type, name, description, `order`, is_active) VALUES (1, 'Submitted', 'mock desc', 1, 1);
INSERT INTO SUBMISSION_TYPE (pk_submission_type, name, description, `order`, is_active) VALUES (2, 'Completed', 'mock desc', 2, 1);
INSERT INTO SUBMISSION_TYPE (pk_submission_type, name, description, `order`, is_active) VALUES (3, 'Cancelled', 'mock desc', 3, 1);

-- ==================================================================================
-- 13. AGGIORNAMENTI ERROR_TYPE (dati – eseguire dopo eventuale insert dati statici)
-- ==================================================================================
-- Decommentare e eseguire se serve allineare name/description ai codici WRN/ERR

/*
UPDATE ERROR_TYPE SET name = 'Invalid format (Transato)', description = 'The field does not comply with the expected format' WHERE error_code = 'WRN1';
UPDATE ERROR_TYPE SET name = 'Invalid date format (Transato)', description = 'The field does not comply with the expected date format' WHERE error_code = 'WRN2';
UPDATE ERROR_TYPE SET name = 'Mandatory data is missing (Transato)', description = 'The mandatory field is missing or empty' WHERE error_code = 'WRN3';
UPDATE ERROR_TYPE SET name = 'Invalid value (Transato)', description = 'The field contains a value that is not allowed or not valid' WHERE error_code = 'WRN4';
UPDATE ERROR_TYPE SET name = 'Key fields not matching', description = 'The record fields ID-INTERMEDIARIO and ID-ESERCENTE do not match any pair in the "Anagrafica Esercenti" input file' WHERE error_code = 'ERR1';
UPDATE ERROR_TYPE SET name = 'Invalid lenght (Transato)', description = 'The record has an invalid length (not equal to 250 characters)' WHERE error_code = 'ERR2';
*/

-- ==================================================================================
-- FINE SCRIPT
-- ==================================================================================
-- Script one-off non inclusi (usare solo se necessario):
-- - Script_Fix_IMP_OPE_Decimal.sql (correzione dati imp_ope già importati)
-- - Script_DB_Fix_Connection_Issues.sql (diagnostica/lock/timeout)
-- ==================================================================================
