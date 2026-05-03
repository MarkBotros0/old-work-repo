package it.deloitte.postrxade.security.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import it.deloitte.postrxade.utils.ForwardedHostUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	private static final Logger LOGGER = LoggerFactory.getLogger(CustomAuthorizationRequestResolver.class);
	private static final String LOGGER_MSG_BEGIN = "Inizio [hashcode={}]";
	private static final String LOGGER_MSG_END = "Fine";

	// oauth

	private final StringKeyGenerator secureKeyGenerator = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

	private final OAuth2AuthorizationRequestResolver defaultAuthorizationRequestResolver;

	public CustomAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());
		this.defaultAuthorizationRequestResolver = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/api/authorization");
		LOGGER.debug(LOGGER_MSG_END);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());

		OAuth2AuthorizationRequest authorizationRequest = this.defaultAuthorizationRequestResolver.resolve(request);

		// return authorizationRequest != null ?
		// 		customAuthorizationRequest(authorizationRequest) :
		// 		null;

		OAuth2AuthorizationRequest result = null;

		if (authorizationRequest != null) {
			result = customAuthorizationRequest(request, authorizationRequest);
		}
		else {
			LOGGER.debug("authorizationRequest is NULL");
		}

		LOGGER.debug(LOGGER_MSG_END);
		return result;
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());
		LOGGER.debug("clientRegistrationId={}", clientRegistrationId);

		OAuth2AuthorizationRequest authorizationRequest = this.defaultAuthorizationRequestResolver.resolve(request, clientRegistrationId);

		OAuth2AuthorizationRequest result = null;

		if (authorizationRequest != null) {
			result = customAuthorizationRequest(request, authorizationRequest);
		}
		else {
			LOGGER.debug("authorizationRequest is NULL");
		}

		LOGGER.debug(LOGGER_MSG_END);
		return result;
	}

	private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
	private static final String X_FORWARDED_PORT = "X-Forwarded-Port";

	/**
	 * Costruisce la redirect URI usata nella richiesta OAuth2 authorize.
	 * <p>
	 * Come la calcoliamo:
	 * <ol>
	 *   <li><b>Schema (http/https)</b>: da header {@code X-Forwarded-Proto} se presente (dietro proxy/load balancer),
	 *       altrimenti da {@code request.getScheme()}. Così se il client arriva in HTTPS, la redirect è HTTPS.</li>
	 *   <li><b>Host</b>: da {@code X-Forwarded-Host} se presente (es. amex-be.testpos-noprod.com),
	 *       altrimenti da {@code request.getServerName()}. Così la redirect punta all’host reale della richiesta.</li>
	 *   <li><b>Porta</b>: non viene mai inclusa nell’URL quando è 80 o 443 (porte standard), così l’URI è pulito
	 *       (nessun {@code :80} o {@code :443}). Per altre porte si usa {@code X-Forwarded-Port} o
	 *       {@code request.getServerPort()} e si aggiunge {@code :porta}.</li>
	 *   <li><b>Path</b>: {@code contextPath + "/api/login/oauth2/code/" + clientRegistrationId}</li>
	 * </ol>
 * Esempio: su richiesta a {@code https://amex-be.testpos-noprod.com} con provider {@code oidc-deloitte}
 * → {@code https://amex-be.testpos-noprod.com/api/login/oauth2/code/oidc-deloitte}.
	 */
	private static String buildRedirectUriFromRequest(HttpServletRequest request, String clientRegistrationId) {
		String scheme = forwardedProtoOrScheme(request);
		String host = ForwardedHostUtils.getHostFromRequest(request);
		int port = forwardedPortOrServerPort(request);
		// Non mostrare mai la porta se è 80 o 443 (evita :80 su https quando il proxy manda port=80)
		boolean omitPort = (port == 80 || port == 443);
		String portPart = omitPort ? "" : ":" + port;
		String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
		String path = contextPath + "/api/login/oauth2/code/" + clientRegistrationId;
		return scheme + "://" + host + portPart + path;
	}

	private static String forwardedProtoOrScheme(HttpServletRequest request) {
		String proto = request.getHeader(X_FORWARDED_PROTO);
		if (proto != null && !proto.isBlank()) {
			return proto.strip().toLowerCase();
		}
		return request.getScheme();
	}

	private static int forwardedPortOrServerPort(HttpServletRequest request) {
		String port = request.getHeader(X_FORWARDED_PORT);
		if (port != null && !port.isBlank()) {
			try {
				return Integer.parseInt(port.strip());
			} catch (NumberFormatException ignored) {
			}
		}
		return request.getServerPort();
	}

	private static String getClientRegistrationIdFromRedirectUri(String redirectUri) {
		if (redirectUri == null || !redirectUri.contains("/code/")) {
			return null;
		}
		return redirectUri.substring(redirectUri.indexOf("/code/") + 6);
	}

	private OAuth2AuthorizationRequest customAuthorizationRequest(HttpServletRequest request, OAuth2AuthorizationRequest req) {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());

		OAuth2AuthorizationRequest result = null;

		if (req != null) {
			String registrationId = getClientRegistrationIdFromRedirectUri(req.getRedirectUri());
			String requestBasedRedirectUri = registrationId != null
				? buildRedirectUriFromRequest(request, registrationId)
				: req.getRedirectUri();
			if (requestBasedRedirectUri != null && !requestBasedRedirectUri.equals(req.getRedirectUri())) {
				LOGGER.debug("Overriding redirect_uri with request host: {} -> {}", req.getRedirectUri(), requestBasedRedirectUri);
			}

			Map<String, Object> attributes = new HashMap<>(req.getAttributes());
			Map<String, Object> additionalParameters = new HashMap<>(req.getAdditionalParameters());
			addPkceParameters(attributes, additionalParameters);
			result = OAuth2AuthorizationRequest.from(req)
				.redirectUri(requestBasedRedirectUri)
				.attributes(attributes)
				.additionalParameters(additionalParameters)
				.build();
		}

		LOGGER.debug(LOGGER_MSG_END);
		return result;
	}

	private void addPkceParameters(Map<String, Object> attributes, Map<String, Object> additionalParameters) {
		LOGGER.debug(LOGGER_MSG_BEGIN, this.hashCode());

		String codeVerifier = this.secureKeyGenerator.generateKey();
		attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
		try {
			String codeChallenge = createHash(codeVerifier);
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE, codeChallenge);
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
		}
		catch (NoSuchAlgorithmException e) {
			additionalParameters.put(PkceParameterNames.CODE_CHALLENGE, codeVerifier);
		}

		LOGGER.debug(LOGGER_MSG_END);
	}

	private static String createHash(String value) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] digest = md.digest(value.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

}
