package it.deloitte.postrxade.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.deloitte.postrxade.utils.ForwardedHostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that intercepts requests to /api/authorization/oidc and redirects
 * to the SSO selection page if the request doesn't contain OAuth2 parameters.
 * <p>
 * The redirect URL uses the same host the client used to call the BE (X-Forwarded-Host
 * when behind a proxy, else Host / getServerName()), so e.g. aziendab-be.testpos-noprod.com
 * or amex-be.posdatareporting.deloitte.it.
 */
@Component
@Order(1)
@org.springframework.context.annotation.Profile("!batch & !output")
public class SsoSelectionFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SsoSelectionFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        String queryString = request.getQueryString();

        // Check if this is a request to /api/authorization/oidc without OAuth2 parameters
        if ("/api/authorization/oidc".equals(requestURI)) {
            // Check if this is an OAuth2 callback or a request with OAuth2 parameters
            boolean isOAuth2Request = queryString != null && (
                queryString.contains("response_type") ||
                queryString.contains("client_id") ||
                queryString.contains("redirect_uri") ||
                queryString.contains("state") ||
                queryString.contains("code")
            );

            // Check if this is a request from the SSO selection page (link is /api/authorization/oidc?provider=oidc)
            boolean isFromSsoSelection = queryString != null && queryString.contains("provider=oidc");

            if (!isOAuth2Request && !isFromSsoSelection) {
                // Redirect to SSO selection page. Host = quello con cui il client ha chiamato il BE
                // (X-Forwarded-Host dietro proxy, altrimenti Host / getServerName()).
                String scheme = forwardedProtoOrScheme(request);
                String host = ForwardedHostUtils.getHostFromRequest(request);
                String redirectUrl = scheme + "://" + host + "/sso-select";
                LOGGER.debug("Redirecting to SSO selection page: {}", redirectUrl);
                response.sendRedirect(redirectUrl);
                return;
            }
            
            // If it's from SSO selection page, let Spring Security handle it (it will ignore the provider parameter)
        }

        // Continue with the filter chain
        filterChain.doFilter(request, response);
    }

    private static String forwardedProtoOrScheme(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto != null && !proto.isBlank()) {
            return proto.strip().toLowerCase();
        }
        return request.getScheme();
    }
}

