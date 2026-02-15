package it.deloitte.postrxade.web.rest;

import it.deloitte.postrxade.tenant.TenantConfiguration;
import it.deloitte.postrxade.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for SSO provider selection page.
 * <p>
 * This controller displays a selection page where users can choose between
 * different SSO providers based on their tenant configuration.
 */
@Controller
public class SsoSelectionController {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SsoSelectionController.class);
    
    private final TenantConfiguration tenantConfiguration;
    
    public SsoSelectionController(TenantConfiguration tenantConfiguration) {
        this.tenantConfiguration = tenantConfiguration;
    }

    /**
     * Displays the SSO provider selection page.
     * <p>
     * Endpoint: GET /sso-select
     * <p>
     * This endpoint shows a page with buttons to select the desired SSO provider.
     * Only SSO providers configured for the current tenant are displayed.
     * When a user clicks on a provider button, they are redirected to the
     * corresponding OAuth2 authorization endpoint handled by Spring Security.
     *
     * @param model The Spring MVC model used to pass attributes to the view template.
     * @return The name of the view to render ("sso-selection").
     */
    @GetMapping("/sso-select")
    public String ssoSelection(Model model) {
        String tenantId = TenantContext.getTenantId();
        LOGGER.debug("Loading SSO selection page for tenant: {}", tenantId);
        
        if (tenantId == null) {
            LOGGER.error("No tenant context available for SSO selection");
            throw new IllegalStateException("Tenant context is not set");
        }
        
        // Get SSO providers for this tenant
        List<String> ssoProviders = tenantConfiguration.getSsoProvidersForTenant(tenantId);
        LOGGER.info("SSO providers for tenant {}: {}", tenantId, ssoProviders);
        
        // Coppie (id, label) già risolte: nexi = Nexi, Deloitte; amex = Amex, Deloitte
        List<Map<String, String>> ssoOptions = new ArrayList<>();
        for (String providerId : ssoProviders) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("id", providerId);
            option.put("label", tenantConfiguration.getProviderDisplayName(providerId));
            ssoOptions.add(option);
        }
        
        model.addAttribute("ssoOptions", ssoOptions);
        model.addAttribute("tenantId", tenantId);
        
        return "sso-selection";
    }
}

