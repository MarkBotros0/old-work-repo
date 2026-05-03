package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.dto.AuditLogsSearchDTO;
import it.deloitte.postrxade.dto.LogDTO;
import it.deloitte.postrxade.entity.Log;
import it.deloitte.postrxade.repository.LogRepository;
import it.deloitte.postrxade.service.AuditTrailService;
import it.deloitte.postrxade.utils.SortItem;
import it.deloitte.postrxade.utils.Utils;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;


/**
 * Implementation of the AuditTrailService.
 * <p>
 * This service handles the retrieval of system logs (Audit Trail).
 * It supports server-side pagination and sorting to efficiently handle large volumes of log data.
 * <p>
 * Note: While the search DTO may contain filtering criteria (like period or fiscal year),
 * the current implementation focuses on retrieving the full paginated list.
 */
@Service
@Transactional
public class AuditTrailServiceImpl implements AuditTrailService {

    @Autowired
    @Qualifier("alternativeMapperFacade")
    private MapperFacade alternativeMapperFacade;

    private final LogRepository logRepository;

    /**
     * Constructor-based dependency injection.
     *
     * @param logRepository The repository to access Log entities.
     */
    public AuditTrailServiceImpl(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    /**
     * Retrieves a paginated list of audit logs.
     * <p>
     * Logic:
     * 1. Extracts pagination and sorting from the search DTO.
     * 2. Loads Log entities with associations eagerly (JOIN FETCH in repository) so that Orika
     *    can map to LogDTO without triggering lazy load (no LazyInitializationException on later pages).
     * 3. Maps {@link Log} to {@link LogDTO} via Orika.
     *
     * @param searchDTO The DTO containing pagination and sorting parameters.
     * @return A {@link Page} of {@link LogDTO}.
     */
    @Override
    public Page<LogDTO> getAuditLogs(AuditLogsSearchDTO searchDTO) {
        int pageSize = searchDTO.getSize() != null ? searchDTO.getSize() : 10;
        int pageIndex = normalizePageIndex(searchDTO.getPage());
        List<SortItem> sortList = searchDTO.getSortList();

        long count = logRepository.count();
        Pageable pageable = Utils.createPageableBasedOnPageAndSizeAndSorting(sortList, pageIndex, pageSize);
        Page<Log> recordsFromDb = logRepository.getAllLogsUsingPagination(pageable);

        List<LogDTO> logDTOList = new ArrayList<>(
                alternativeMapperFacade.mapAsList(recordsFromDb.getContent(), LogDTO.class)
        );
        return new PageImpl<>(logDTOList, pageable, count);
    }

    /**
     * Normalizes page index for Spring Data (0-based). Contract: client sends 0-based (0 = first page).
     * Only ensures non-null and non-negative.
     */
    private static int normalizePageIndex(Integer page) {
        return (page == null || page < 0) ? 0 : page;
    }
}
