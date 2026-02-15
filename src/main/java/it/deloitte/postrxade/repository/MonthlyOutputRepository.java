package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.MonthlyOutput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for MonthlyOutput entity.
 */
@Repository
public interface MonthlyOutputRepository extends JpaRepository<MonthlyOutput, Long> {
    
    // Basic findById is inherited from JpaRepository
}





