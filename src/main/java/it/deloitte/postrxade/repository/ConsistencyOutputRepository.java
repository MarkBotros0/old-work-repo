package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.ConsistencyOutput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for ConsistencyOutput entity.
 */
@Repository
public interface ConsistencyOutputRepository extends JpaRepository<ConsistencyOutput, Long> {
    
    // Basic findById is inherited from JpaRepository
}



