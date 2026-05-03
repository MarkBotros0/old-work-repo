package it.deloitte.postrxade.repository;

public class OutputTransactionLineMapEntry {
    private final Long transactionId;
    private final int rowNum;

    public OutputTransactionLineMapEntry(Long transactionId, int rowNum) {
        this.transactionId = transactionId;
        this.rowNum = rowNum;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public int getRowNum() {
        return rowNum;
    }
}
