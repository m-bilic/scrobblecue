package com.scrobblecue.common;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.credentials.CredentialDelivery;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * The OpenAPI description that clients are generated from (committed as {@code api/openapi.json}).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static {
		// The bundle is resolved from the Authorization header or cookie, not from the request body
		SpringDocUtils.getConfig().addRequestWrapperToIgnore(CredentialBundle.class);
	}

	@Bean
	OpenAPI scrobbleCueOpenApi() {
		return new OpenAPI()
			.info(new Info().title("ScrobbleCue API")
				.version("v1")
				.description("Stateless API: signs and forwards requests to Last.fm and Discogs, prepares "
						+ "tracklists and timestamps. Stores no user data.")
				.license(new License().name("MIT")))
			.components(new Components()
				.addSecuritySchemes("bundle", new SecurityScheme().type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.description("Sealed credential bundle (apps)"))
				.addSecuritySchemes("cookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
					.in(SecurityScheme.In.COOKIE)
					.name(CredentialDelivery.COOKIE_NAME)
					.description("Sealed credential bundle (website); write requests also need X-ScrobbleCue")));
	}

}
