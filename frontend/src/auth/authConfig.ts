import { WebStorageStateStore } from 'oidc-client-ts'
import type { AuthProviderProps } from 'react-oidc-context'

/** Response of the facade's public GET /api/auth/config */
interface AuthConfigResponse {
  issuerUri: string
  clientId: string
}

/**
 * Builds the OIDC client settings from the facade's /api/auth/config, so the issuer and client id
 * are not compiled into the bundle. Flow: Authorization Code + PKCE against Keycloak; the redirect
 * comes back to this page (http://localhost:3000/ in dev, http://localhost:8080/ from the JAR).
 */
export async function loadAuthConfig(): Promise<AuthProviderProps> {
  const response = await fetch('/api/auth/config')
  if (!response.ok) {
    throw new Error(`Cannot load sign-in settings (HTTP ${response.status}). Is the facade backend running?`)
  }
  const { issuerUri, clientId } = (await response.json()) as AuthConfigResponse

  return {
    authority: issuerUri,
    client_id: clientId,
    redirect_uri: window.location.origin + '/',
    post_logout_redirect_uri: window.location.origin + '/',
    scope: 'openid profile email',
    // Renew the access token (5 min in Keycloak) with the refresh token before it expires
    automaticSilentRenew: true,
    // Tokens survive a page reload but not closing the tab
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    // Remove ?code=...&state=... from the address bar after the redirect back from Keycloak
    onSigninCallback: () => {
      window.history.replaceState({}, document.title, window.location.pathname)
    },
  }
}
