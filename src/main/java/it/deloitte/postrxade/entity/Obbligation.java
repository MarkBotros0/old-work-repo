package it.deloitte.postrxade.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

/**
 * Entity representing an Obligation in the system.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "OBBLIGATION")
public class Obbligation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk_obbligation")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fk_period")
    private Period period;

    @Column(name = "fiscalYear")
    private Integer fiscalYear;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "obbligation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Submission> submissions;

    public Obbligation(Long id, Period period, Integer fiscalYear) {
        this.id = id;
        this.period = period;
        this.fiscalYear = fiscalYear;
    }
}
