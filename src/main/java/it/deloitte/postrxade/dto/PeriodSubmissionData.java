package it.deloitte.postrxade.dto; // or your preferred package

import it.deloitte.postrxade.entity.Obbligation;
import it.deloitte.postrxade.entity.Submission;
import java.util.Collections;
import java.util.List;

/**
 * A wrapper to hold the requested FY/Period *and* the Obligation
 * (if found), along with its submissions.
 */
public record PeriodSubmissionData(
        Integer fiscalYear,
        String periodName,
        Obbligation obligation, // This can be null
        List<Submission> submissions
) {
    /**
     * Constructor for when an Obligation IS found.
     * It populates all fields from the Obligation.
     */
    public PeriodSubmissionData(Obbligation obligation) {
        this(
                obligation.getFiscalYear(),
                obligation.getPeriod().getName(),
                obligation,
                (obligation.getSubmissions() != null) ? obligation.getSubmissions() : Collections.emptyList()
        );
    }

    /**
     * Constructor for when an Obligation is NOT found.
     * It populates the requested FY/Period and leaves the obligation null
     * and submissions empty.
     */
    public PeriodSubmissionData(Integer fiscalYear, String periodName) {
        this(fiscalYear, periodName, null, Collections.emptyList());
    }
}