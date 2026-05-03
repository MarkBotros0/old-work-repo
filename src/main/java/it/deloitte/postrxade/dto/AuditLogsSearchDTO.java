package it.deloitte.postrxade.dto;

import it.deloitte.postrxade.utils.SortItem;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@Data
@RequiredArgsConstructor
public class AuditLogsSearchDTO {


    private String fiscalYear;

    private String period;

    /**
     * Page index (0-based: 0 = first page).
     * The service also accepts 1-based value (1 = first page) and converts it internally.
     */
    @PositiveOrZero(message = "page must be zero or a positive number")
    private Integer page;


    /**
     * Number of elements per page. Default applied in service if null.
     */
    @Positive(message = "size must be a positive number")
    private Integer size;

    private List<SortItem> sortList;
}
