package org.fsweb.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenID Connect settings the UI needs to sign in ({@code facade.auth.*} in application.yml),
 * served by {@code GET /api/auth/config}.
 *
 * @param issuerUri Keycloak realm URL, e.g. {@code http://localhost:8180/realms/fsweb}; the same
 *                  value as {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * @param clientId  public OIDC client the UI signs in with
 */
@ConfigurationProperties("facade.auth")
public record AuthProperties(
        String issuerUri,
        @DefaultValue("fs-web-ui") String clientId) {
}
