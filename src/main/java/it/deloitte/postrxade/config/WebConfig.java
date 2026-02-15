package it.deloitte.postrxade.config;

import java.util.ArrayList;
import java.util.List;

import it.deloitte.postrxade.tenant.TenantConfiguration;
import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Configuration of web application with Servlet 3.0 APIs.
 * CORS è per-tenant: ogni BE (es. amex-be) accetta solo le origini del proprio tenant (FE + BE).
 * Host non tenant-specific (es. *.awsapprunner.com) ammette tutte le origini configurate.
 */
@Configuration
@org.springframework.context.annotation.Profile("!batch & !output")
public class WebConfig implements ServletContextInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(WebConfig.class);
	private static final String LOGGER_MSG_BEGIN = "Inizio [hashcode={}]";
	private static final String LOGGER_MSG_END = "Fine";

	@Value("${application.cors.allowed-origins:}")
	private String configCorsAllowedOriginsExtra;

	/** Base domain for tenant URLs (e.g. testpos-noprod.com). Origins built as https://{tenantId}.{baseDomain} and https://{tenantId}-be.{baseDomain}. */
	@Value("${application.cors.tenant-base-domain:testpos-noprod.com}")
	private String corsTenantBaseDomain;

	@Value("${application.cors.allowed-methods:null}")
	private List<String> configCorsAllowedMethods;

	@Value("${application.cors.allowed-headers:null}")
	private List<String> configCorsAllowedHeaders;

	@Value("${application.cors.exposed-headers:null}")
	private List<String> configCorsExposedHeaders;

	@Value("${application.cors.allow-credentials:null}")
	private Boolean configCorsAllowedCredentials;

	@Value("${application.cors.max-age:null}")
	private Long configCorsMaxAge;

	private final Environment env;
	private final TenantConfiguration tenantConfiguration;

	public WebConfig(Environment env, TenantConfiguration tenantConfiguration) {
		this.env = env;
		this.tenantConfiguration = tenantConfiguration;
	}

	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		if (env.getActiveProfiles().length != 0) {
			LOGGER.info("Web application configuration, using profiles: {}", (Object[]) env.getActiveProfiles());
		}

		LOGGER.info("Web application fully configured");
	}

	@Bean
	public CorsFilter corsFilter() {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());

		List<String> extraOriginsList = new ArrayList<>();
		if (StringUtils.hasText(configCorsAllowedOriginsExtra)) {
			for (String origin : configCorsAllowedOriginsExtra.split("\\s*,\\s*")) {
				String o = origin.trim();
				if (!o.isEmpty()) {
					extraOriginsList.add(o);
				}
			}
		}

		CorsConfiguration baseConfig = new CorsConfiguration();
		if (configCorsAllowedMethods != null) {
			baseConfig.setAllowedMethods(configCorsAllowedMethods);
		}
		if (configCorsAllowedHeaders != null) {
			baseConfig.setAllowedHeaders(configCorsAllowedHeaders);
		}
		if (configCorsExposedHeaders != null) {
			baseConfig.setExposedHeaders(configCorsExposedHeaders);
		}
		if (configCorsAllowedCredentials != null) {
			baseConfig.setAllowCredentials(configCorsAllowedCredentials);
		}
		if (configCorsMaxAge != null) {
			baseConfig.setMaxAge(configCorsMaxAge);
		}

		CorsConfigurationSource source = new TenantCorsConfigurationSource(
			corsTenantBaseDomain,
			tenantConfiguration,
			extraOriginsList,
			baseConfig
		);
		LOGGER.info("CORS per-tenant enabled (base domain: {}): each BE allows only its FE origin", corsTenantBaseDomain);

		LOGGER.debug(LOGGER_MSG_END);
		return new CorsFilter(source);
	}
}
