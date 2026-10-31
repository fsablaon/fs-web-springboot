package org.fsweb.facade.controller;

import org.fsweb.facade.dto.UserInfo;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in user, from the validated access token (no call to Keycloak). */
@RestController
@RequestMapping("/api")
public class MeController {

    private static final String ROLE_PREFIX = "ROLE_";

    @GetMapping("/me")
    public UserInfo me(JwtAuthenticationToken authentication) {
        var jwt = authentication.getToken();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .sorted()
                .toList();
        return new UserInfo(
                authentication.getName(),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"),
                roles);
    }
}
