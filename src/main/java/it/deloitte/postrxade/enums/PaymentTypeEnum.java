package it.deloitte.postrxade.enums;

import lombok.Getter;

/**
 * Enumeration for Transaction Payment Types.
 * <p>
 * Handles the mapping between the raw database code and the user-friendly label:
 * "00" = POS, "01" = E-commerce.
 */
@Getter
public enum PaymentTypeEnum {

    POS("00", "POS"),
    E_COMMERCE("01", "E-commerce"),
    UNKNOWN("UNKNOWN", "Unknown");

    private final String code;
    private final String label;

    PaymentTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * Resolves the label based on the raw code.
     * Contains the specific business logic that was previously hardcoded.
     */
    public static PaymentTypeEnum fromCode(String code) {
        if (code == null) return UNKNOWN;

        // Exact match for E-Commerce
        if (E_COMMERCE.code.equalsIgnoreCase(code)) {
            return E_COMMERCE;
        }
        // Exact match for POS
        if (POS.code.equalsIgnoreCase(code)) {
            return POS;
        }
        return UNKNOWN;
    }
}
