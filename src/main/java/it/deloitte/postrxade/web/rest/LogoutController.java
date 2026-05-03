package it.deloitte.postrxade.web.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST controller per il logout dell'utente autenticato via Bearer JWT.
 * <p>
 * Invalida la sessione HTTP lato server e restituisce 204 senza redirect.
 * Il frontend dopo la risposta deve cancellare il token locale e navigare alla homepage;
 * la successiva chiamata a GET /api/account non autenticata restituirà 401 e il FE
 * avvierà il flusso OIDC (redirect a sso-select, ecc.).
 * </p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Logout", description = "Logout dell'utente autenticato (Bearer token)")
public class LogoutController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogoutController.class);

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    /**
     * Esegue il logout: invalida la sessione e il SecurityContext, risponde 204 senza redirect.
     * Richiede autenticazione (Bearer token).
     *
     * @param request       richiesta HTTP
     * @param response      risposta HTTP
     * @param authentication autenticazione corrente (può essere null se non autenticato)
     * @return 204 No Content in caso di successo
     */
    @PostMapping("/logout")
    @Operation(
        summary = "Logout",
        description = "Invalida la sessione lato server e restituisce 204. Il FE deve poi cancellare il token e navigare alla homepage; il flusso 401 → OIDC si attiverà alle chiamate successive."
    )
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) {
        if (authentication != null) {
            LOGGER.info("Logout for user: {}", authentication.getName());
            logoutHandler.logout(request, response, authentication);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
