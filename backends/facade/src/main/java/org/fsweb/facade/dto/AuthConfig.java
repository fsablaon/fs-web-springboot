package org.fsweb.facade.dto;

/** Response of {@code GET /api/auth/config}: where and as which client the UI signs in. */
public record AuthConfig(String issuerUri, String clientId) {
}
