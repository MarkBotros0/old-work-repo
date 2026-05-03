package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.repository.OutputTransactionLineMapEntry;
import it.deloitte.postrxade.repository.OutputTransactionLineMapRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.StringJoiner;

@Repository
public class OutputTransactionLineMapRepositoryImpl implements OutputTransactionLineMapRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final int INSERT_BATCH_SIZE = 200;

    @Override
    public int bulkInsert(Long outputId, List<OutputTransactionLineMapEntry> entries) {
        if (outputId == null || entries == null || entries.isEmpty()) {
            return 0;
        }

        int insertedRows = 0;
        for (int i = 0; i < entries.size(); i += INSERT_BATCH_SIZE) {
            int endIndex = Math.min(i + INSERT_BATCH_SIZE, entries.size());
            List<OutputTransactionLineMapEntry> batch = entries.subList(i, endIndex);

            StringJoiner values = new StringJoiner(", ");
            for (int j = 0; j < batch.size(); j++) {
                values.add("(:fk_output_" + j + ", :fk_transaction_" + j + ", :row_num_" + j + ", CURRENT_TIMESTAMP)");
            }

            String sql = String.format("""
                INSERT INTO OUTPUT_TRANSACTION_LINE_MAP (fk_output, fk_transaction, row_num, created_at)
                VALUES %s
                ON DUPLICATE KEY UPDATE
                  fk_transaction = VALUES(fk_transaction)
                """, values);

            Query query = entityManager.createNativeQuery(sql);
            for (int j = 0; j < batch.size(); j++) {
                OutputTransactionLineMapEntry entry = batch.get(j);
                query.setParameter("fk_output_" + j, outputId);
                query.setParameter("fk_transaction_" + j, entry.getTransactionId());
                query.setParameter("row_num_" + j, entry.getRowNum());
            }

            insertedRows += query.executeUpdate();
        }

        return insertedRows;
    }
}
