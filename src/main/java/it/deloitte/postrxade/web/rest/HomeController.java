package it.deloitte.postrxade.web.rest;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;

/**
 * Controller for serving the application entry point (Home Page).
 * <p>
 * Unlike the API controllers, this class uses {@link Controller} to serve an HTML view ("index").
 * It interacts with the Spring Security context to inject authentication state directly into the
 * initial page load, allowing the frontend to render appropriate UI elements (like the user's name)
 * without needing an immediate secondary API call.
 * <p>
 * The home page is exposed only when profile is "local". For any other profile (dev, prod, etc.)
 * GET / returns 404 to avoid exposing UI or info.
 */
@Controller
public class HomeController {

    private final Environment environment;

    public HomeController(Environment environment) {
        this.environment = environment;
    }

    private boolean isLocalProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return Arrays.stream(activeProfiles).anyMatch("local"::equalsIgnoreCase);
    }

    /**
     * Handles the root URL request and prepares the "index" view.
     * <p>
     * Endpoint: GET /
     * <p>
     * The index page is shown only with profile "local". Otherwise returns 404.
     *
     * @param model The Spring MVC model used to pass attributes to the view template.
     * @return The name of the view to render ("index"), or 404 when not in local profile.
     */
    @GetMapping("/")
    public Object home(Model model) {
        if (!isLocalProfile()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Check if user is logged in (and not just an anonymous token)
        boolean isAuthenticated = authentication != null && 
                                 authentication.isAuthenticated() && 
                                 !"anonymousUser".equals(authentication.getPrincipal());
        
        model.addAttribute("isAuthenticated", isAuthenticated);

        // If logged in via OAuth2, extract user details for the UI greeting
        if (isAuthenticated && authentication.getPrincipal() instanceof OAuth2User oauth2User) {
            String userName = oauth2User.getAttribute("name");
            String userEmail = oauth2User.getAttribute("email");

            // Prefer name, fallback to email
            model.addAttribute("userName", userName != null ? userName : userEmail);
        }
        
        return "index";
    }
}
