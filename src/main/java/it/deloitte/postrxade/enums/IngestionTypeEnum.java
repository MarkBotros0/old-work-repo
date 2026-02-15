package it.deloitte.postrxade.enums;

import lombok.Getter;

/**
 * Enumeration for Ingestion Types.
 * Replaces magic strings "transato" and "anagrafe".
 */
@Getter
public enum IngestionTypeEnum {

    TRANSACTIONS("transato"),
    MERCHANT("anagrafe");

    private final String label;

    IngestionTypeEnum(String label) {
        this.label = label;
    }
}