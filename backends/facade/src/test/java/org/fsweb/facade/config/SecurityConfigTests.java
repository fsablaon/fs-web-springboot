package org.fsweb.facade.config;

import org.fsweb.facade.event.VisitEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real security filter chain. The JwtDecoder is mocked, so no call to Keycloak is made:
 * a request with {@code Authorization: Bearer good-token} is decoded to {@link #franceToken()}.
 */
@SpringBootTest(properties = {
        "management.opentelemetry.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
@AutoConfigureMockMvc
class SecurityConfigTests {

    private static final String GOOD_TOKEN = "good-token";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private VisitEventPublisher visitEventPublisher;

    @Test
    void helloWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/hello"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));

        verify(visitEventPublisher, never()).publish(anyString());
    }

    @Test
    void helloWithInvalidTokenIsUnauthorized() throws Exception {
        when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("expired"));

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")));
    }

    @Test
    void helloWithTokenGreetsSignedInUserAndPublishesVisit() throws Exception {
        when(jwtDecoder.decode(GOOD_TOKEN)).thenReturn(franceToken());

        mvc.perform(get("/api/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, france!"));

        verify(visitEventPublisher).publish("/api/hello");
    }

    @Test
    void helloWithNameParameterUsesIt() throws Exception {
        mvc.perform(get("/api/hello").param("name", "Bob")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "alice"))))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, Bob!"));
    }

    @Test
    void meReturnsUserAndRealmRolesFromToken() throws Exception {
        when(jwtDecoder.decode(GOOD_TOKEN)).thenReturn(franceToken());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("france"))
                .andExpect(jsonPath("$.name").value("France Dev"))
                .andExpect(jsonPath("$.email").value("france@example.com"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("admin", "user")));
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void overviewWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void authConfigIsPublic() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerUri").value("http://localhost:8180/realms/fsweb"))
                .andExpect(jsonPath("$.clientId").value("fs-web-ui"));
    }

    @Test
    void healthIsPublicButMetricsNeedToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void reactAppIsPublic() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/some/spa/route")).andExpect(status().isOk());
    }

    /** What Keycloak puts in an access token for user france (realm fsweb, client fs-web-ui). */
    private static Jwt franceToken() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(GOOD_TOKEN)
                .header("alg", "RS256")
                .issuer("http://localhost:8180/realms/fsweb")
                .subject("8f1c2d3e-0000-0000-0000-000000000001")
                .audience(List.of("facade"))
                .claim("azp", "fs-web-ui")
                .claim("preferred_username", "france")
                .claim("name", "France Dev")
                .claim("email", "france@example.com")
                .claim("realm_access", Map.of("roles", List.of("user", "admin")))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
