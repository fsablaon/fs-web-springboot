/** fetch() with `Authorization: Bearer <access token>` when a token is given. */
export function apiFetch(input: string, accessToken: string | undefined, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return fetch(input, { ...init, headers })
}
