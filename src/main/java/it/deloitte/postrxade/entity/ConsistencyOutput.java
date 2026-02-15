package it.deloitte.postrxade.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Entity representing a Consistency Output in the system.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "CONSISTENCY_OUTPUT")
public class ConsistencyOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk_consistency_output")
    private Long id;

    @Column(name = "tp_rec", length = 1)
    private String tpRec;

    @Column(name = "cf_sogg_obbligato", length = 16)
    private String cfSoggObbligato;

    @Column(name = "tipo_ope", length = 2)
    private String tipoOpe;

    @Column(name = "mese_ope")
    private Integer meseOpe;

    @Column(name = "anno_ope")
    private Integer annoOpe;

    @Column(name = "imp_ope", precision = 14, scale = 2)
    private BigDecimal impOpe;

    @Column(name = "tot_ope")
    private Integer totOpe;

    @Column(name = "id_esercente", length = 30)
    private String idEsercente;

    @Column(name = "id_pos", length = 30)
    private String idPos;

    @Column(name = "tipo_pag", length = 2)
    private String tipoPag;

    @Column(name = "cod_fiscale", length = 16)
    private String codFiscale;

    @Column(name = "partita_iva", length = 11)
    private String partitaIva;

    @Column(name = "chiave_banca", length = 50)
    private String chiaveBanca;

}





