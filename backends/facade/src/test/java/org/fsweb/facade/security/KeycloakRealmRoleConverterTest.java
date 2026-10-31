package org.fsweb.facade.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("user", "admin"))));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_user", "ROLE_admin");
    }

    @Test
    void noRealmAccessClaimMeansNoAuthorities() {
        assertThat(converter.convert(token(Map.of()))).isEmpty();
    }

    @Test
    void realmAccessWithoutRolesMeansNoAuthorities() {
        assertThat(converter.convert(token(Map.of("realm_access", Map.of("other", "x"))))).isEmpty();
    }

    private static Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-id")
                .claims(c -> c.putAll(claims))
                .build();
    }
}
