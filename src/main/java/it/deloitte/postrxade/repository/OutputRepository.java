package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.Output;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutputRepository extends JpaRepository<Output, Long> {
    List<Output> findBySubmissionId(Long submissionId);

    /** Count output files for a submission (for Output File Breakdown). */
    long countBySubmissionId(Long submissionId);
}
