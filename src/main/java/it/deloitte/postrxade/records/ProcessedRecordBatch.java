package it.deloitte.postrxade.records;

import it.deloitte.postrxade.entity.ErrorRecord;
import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.Transaction;

import java.util.List;

public record ProcessedRecordBatch(List<Merchant> merchants,
                                   List<Transaction> transactions,
                                   List<ErrorRecord> errorRecords) {
}