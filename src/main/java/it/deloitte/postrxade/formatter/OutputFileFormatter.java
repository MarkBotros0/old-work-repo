package it.deloitte.postrxade.formatter;

import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Transaction;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@NoArgsConstructor
public final class OutputFileFormatter {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static String os = System.getProperty("os.name").toLowerCase();

    /** @param codiceFiscale codice fiscale per tenant (es. Nexi 04107060966, Amex 14778691007) */
    public static String createHeader(String codiceFiscale) {
        StringBuilder sb = new StringBuilder();

        sb.append("0");

        String currentDate = LocalDate.now().format(formatter);
        sb.append(currentDate);

        String cf = (codiceFiscale != null && !codiceFiscale.isBlank()) ? codiceFiscale.trim() : "04107060966";
        sb.append(rightPad(cf, 16, ' '));

        String codiceComunicazione = "TRA26";
        sb.append(codiceComunicazione);

        String tipoInvo = "0";
        sb.append(tipoInvo);

        sb.append(" ".repeat(24));

        sb.append(" ".repeat(292));

        sb.append('A');

        sb.append(getEndOfLine());

        return sb.toString();
    }

    /** @param codiceFiscale codice fiscale per tenant (es. Nexi 04107060966, Amex 14778691007) */
    public static String createFooter(String codiceFiscale) {
        StringBuilder sb = new StringBuilder();

        String tipoRecod = "9";
        sb.append(tipoRecod);

        String currentDate = LocalDate.now().format(formatter);
        sb.append(currentDate);

        String cf = (codiceFiscale != null && !codiceFiscale.isBlank()) ? codiceFiscale.trim() : "04107060966";
        sb.append(rightPad(cf, 16, ' '));

//        TODO add year var instead of hard coded value
        String codiceComunicazione = "TRA26";
        sb.append(codiceComunicazione);

        String tipoInvo = "0";
        sb.append(tipoInvo);

        sb.append(" ".repeat(24));

        sb.append(" ".repeat(292));

        sb.append('A');

        sb.append(getEndOfLine());

        return sb.toString();
    }

    /** Codice fiscale intermediario per tenant (Nexi 04107060966, Amex 14778691007) – stesso valore in ogni riga dati alla posizione 2. */
    public static String toOutputFileString(Transaction transaction, String codiceFiscale) {
        Merchant merchant = transaction.getMerchant();
        StringBuilder sb = new StringBuilder();

        String tipoRecord = transaction.getTpRec();
        sb.append(tipoRecord);

        String cf = (codiceFiscale != null && !codiceFiscale.isBlank()) ? codiceFiscale.trim() : "04107060966";
        sb.append(rightPad(cf, 16, ' '));

        sb.append(transaction.getTipoOpe());

        sb.append(transaction.getDtOpe().toString());

        String totOpeStr = transaction.getTotOpe().toString();
        sb.append(leftPad(totOpeStr, 9, '0'));

        // IMP-OPE: convertire BigDecimal in formato senza punto decimale (moltiplicare per 100)
        // Esempio: 70.00 -> 7000 -> "000007000" (9 cifre, ultime 2 sono decimali)
        String impOpe = transaction.getImpOpe() != null 
            ? String.valueOf(transaction.getImpOpe().multiply(new java.math.BigDecimal("100")).intValue())
            : "0";
        sb.append(leftPad(impOpe, 9, '0'));

        String currency = transaction.getDivisaOpe();
        sb.append(currency);

        sb.append(rightPad(nullSafe(transaction.getIdEsercente()), 30, ' '));

        sb.append(rightPad(nullSafe(transaction.getIdPos()), 30, ' '));

        sb.append(transaction.getTipoPag());

        String codFiscaleEsercente = merchant.getCodFiscale();
        sb.append(rightPad(codFiscaleEsercente, 16, ' '));

        String partitaIvaEsercente = merchant.getPartitaIva();
        sb.append(rightPad(partitaIvaEsercente, 11, ' '));

        String idSalmov = merchant.getIdSalmov();
        sb.append(rightPad(idSalmov, 50, ' '));

        sb.append(" ".repeat(160));

        sb.append('A');

        sb.append(getEndOfLine());

        return sb.toString();
    }

    /** Codice fiscale intermediario per tenant (Nexi 04107060966, Amex 14778691007) – stesso valore in ogni riga dati alla posizione 2. */
    public static String toOutputFileString(ResolvedTransaction transaction, String codiceFiscale) {
        Merchant merchant = transaction.getMerchant();
        StringBuilder sb = new StringBuilder();

        String tipoRecord = transaction.getTpRec();
        sb.append(tipoRecord);

        String cf = (codiceFiscale != null && !codiceFiscale.isBlank()) ? codiceFiscale.trim() : "04107060966";
        sb.append(rightPad(cf, 16, ' '));

        sb.append(transaction.getTipoOpe());

        sb.append(transaction.getDtOpe().toString());

        String totOpeStr = transaction.getTotOpe().toString();
        sb.append(leftPad(totOpeStr, 9, '0'));

        // IMP-OPE: convertire BigDecimal in formato senza punto decimale (moltiplicare per 100)
        // Esempio: 70.00 -> 7000 -> "000007000" (9 cifre, ultime 2 sono decimali)
        String impOpe = transaction.getImpOpe() != null 
            ? String.valueOf(transaction.getImpOpe().multiply(new java.math.BigDecimal("100")).intValue())
            : "0";
        sb.append(leftPad(impOpe, 9, '0'));

        String currency = transaction.getDivisaOpe();
        sb.append(currency);

        sb.append(rightPad(nullSafe(transaction.getIdEsercente()), 30, ' '));

        sb.append(rightPad(nullSafe(transaction.getIdPos()), 30, ' '));

        sb.append(transaction.getTipoPag());

        String codFiscaleEsercente = merchant.getCodFiscale();
        sb.append(rightPad(codFiscaleEsercente, 16, ' '));

        String partitaIvaEsercente = merchant.getPartitaIva();
        sb.append(rightPad(partitaIvaEsercente, 11, ' '));

        String idSalmov = merchant.getIdSalmov();
        sb.append(rightPad(idSalmov, 50, ' '));

        sb.append(" ".repeat(160));

        sb.append('A');

        sb.append(getEndOfLine());

        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static String leftPad(String value, int length, char padChar) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = value.length(); i < length; i++) {
            sb.append(padChar);
        }
        sb.append(value);
        return sb.toString();
    }

    public static String getEndOfLine() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "\r\n";
        } else {
            return "\n";
        }
    }

    private static String rightPad(String value, int length, char padChar) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = value.length(); i < length; i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}


