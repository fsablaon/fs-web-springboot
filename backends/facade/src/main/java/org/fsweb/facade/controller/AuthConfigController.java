package org.fsweb.facade.controller;

import lombok.RequiredArgsConstructor;
import org.fsweb.facade.config.AuthProperties;
import org.fsweb.facade.dto.AuthConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: tells the UI which OpenID Connect provider and client to sign in with. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthConfigController {

    private final AuthProperties authProperties;

    @GetMapping("/config")
    public AuthConfig config() {
        return new AuthConfig(authProperties.issuerUri(), authProperties.clientId());
    }
}
