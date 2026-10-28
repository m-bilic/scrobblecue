package com.scrobblecue.credentials;

import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controller methods declare a {@link CredentialBundle} parameter. The sealed bundle is read
 * from {@code Authorization: Bearer …} (apps) or the credential cookie (website) and opened for the
 * duration of the request only.
 */
@Component
class CredentialsArgumentResolver implements HandlerMethodArgumentResolver {

	private static final String BEARER = "Bearer ";

	/** Custom header the website sends with every state-changing request (CSRF protection). */
	public static final String CSRF_HEADER = "X-ScrobbleCue";

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final CredentialService credentials;

	CredentialsArgumentResolver(CredentialService credentials) {
		this.credentials = credentials;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == CredentialBundle.class;
	}

	@Override
	public CredentialBundle resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)) {
			return credentials.open(header.substring(BEARER.length()).trim());
		}
		String cookie = cookieBundle(request);
		if (cookie == null) {
			throw new MissingCredentialsException();
		}
		// Cookies are sent automatically by the browser, so cookie-authenticated writes need the custom header
		if (!SAFE_METHODS.contains(request.getMethod()) && request.getHeader(CSRF_HEADER) == null) {
			throw new CsrfHeaderMissingException();
		}
		return credentials.open(cookie);
	}

	private static String cookieBundle(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (CredentialDelivery.COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
					return cookie.getValue();
				}
			}
		}
		return null;
	}

}
