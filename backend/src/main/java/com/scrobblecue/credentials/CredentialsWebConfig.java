package com.scrobblecue.credentials;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class CredentialsWebConfig implements WebMvcConfigurer {

	private final CredentialsArgumentResolver resolver;

	CredentialsWebConfig(CredentialsArgumentResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(resolver);
	}

}
