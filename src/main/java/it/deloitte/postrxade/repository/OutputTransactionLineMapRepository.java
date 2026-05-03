package it.deloitte.postrxade.repository;

import java.util.List;

public interface OutputTransactionLineMapRepository {
    int bulkInsert(Long outputId, List<OutputTransactionLineMapEntry> entries);
}
