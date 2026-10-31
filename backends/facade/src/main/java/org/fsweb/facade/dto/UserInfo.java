package org.fsweb.facade.dto;

import java.util.List;

/** Response of {@code GET /api/me}: the signed-in user, read from the access token. */
public record UserInfo(String username, String name, String email, List<String> roles) {
}
