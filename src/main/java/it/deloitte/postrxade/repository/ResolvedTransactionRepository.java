package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.ResolvedTransaction;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResolvedTransactionRepository extends JpaRepository<ResolvedTransaction, Long>, ResolvedTransactionRepositoryCustom {
	@Modifying
	@Transactional
	@Query(value = "UPDATE RESOLVED_TRANSACTION SET fk_output = :outputId WHERE pk_resolved_transaction IN (:ids)", nativeQuery = true)
	void updateOutputForeignKey(@Param("ids") List<Long> ids, @Param("outputId") Long outputId);
}
