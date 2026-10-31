import { useEffect, useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import styles from './App.module.css'
import { apiFetch } from './auth/apiFetch.ts'

/** Response of GET /api/me (facade) */
interface UserInfo {
  username: string
  name: string | null
  email: string | null
  roles: string[]
}

function App() {
  const auth = useAuth()
  const accessToken = auth.user?.access_token

  const [name, setName] = useState('')
  const [greeting, setGreeting] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [visits, setVisits] = useState<number | null>(null)
  const [visitsError, setVisitsError] = useState(false)
  const [me, setMe] = useState<{ token: string; info: UserInfo } | null>(null)
  const visitRecorded = useRef(false)

  // Record one visit per page load (the ref guard stops StrictMode's double effect run in dev).
  // /api/visits (visitor backend) does not need a token.
  useEffect(() => {
    if (visitRecorded.current) return
    visitRecorded.current = true

    fetch('/api/visits', { method: 'POST' })
      .then((response) => {
        if (!response.ok) throw new Error('Failed to record visit')
        return response.json() as Promise<{ count: number }>
      })
      .then((data) => setVisits(data.count))
      .catch(() => setVisitsError(true))
  }, [])

  // Load the signed-in user (username and roles) from the facade once per access token
  useEffect(() => {
    if (!accessToken) return
    apiFetch('/api/me', accessToken)
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        return response.json() as Promise<UserInfo>
      })
      .then((info) => setMe({ token: accessToken, info }))
      .catch(() => setMe(null))
  }, [accessToken])

  const fetchGreeting = async () => {
    setLoading(true)
    setError('')

    try {
      const response = await apiFetch(`/api/hello${name ? `?name=${encodeURIComponent(name)}` : ''}`, accessToken)

      if (response.status === 401) {
        // Token expired or revoked and could not be renewed: back to the signed-out view
        await auth.removeUser()
        throw new Error('Session expired, sign in again')
      }
      if (!response.ok) {
        throw new Error('Failed to fetch greeting')
      }

      const data = await response.text()
      setGreeting(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred')
    } finally {
      setLoading(false)
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    fetchGreeting()
  }

  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.sub
  const userInfo = me && me.token === accessToken ? me.info : null

  const authBar = auth.isLoading || auth.activeNavigator
    ? <span className={styles.authStatus}>Checking sign-in...</span>
    : auth.isAuthenticated
      ? (
        <>
          <span className={styles.authStatus}>
            Signed in as <strong>{username}</strong>
            {userInfo && userInfo.roles.length > 0 && <> ({userInfo.roles.join(', ')})</>}
          </span>
          <button type="button" className={styles.linkButton} onClick={() => void auth.signoutRedirect()}>
            Sign out
          </button>
        </>
      )
      : (
        <>
          <span className={styles.authStatus}>Not signed in</span>
          <button type="button" className={styles.linkButton} onClick={() => void auth.signinRedirect()}>
            Sign in
          </button>
        </>
      )

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <div className={styles.authBar}>{authBar}</div>

        <h1 className={styles.title}>Spring Boot + React</h1>
        <p className={styles.subtitle}>Full-stack web application</p>

        {auth.isAuthenticated ? (
          <form onSubmit={handleSubmit} className={styles.form}>
            <div className={styles.inputGroup}>
              <label htmlFor="name" className={styles.label}>
                Enter your name:
              </label>
              <input
                id="name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={username ?? 'World'}
                className={styles.input}
              />
            </div>

            <button
              type="submit"
              className={styles.button}
              disabled={loading}
            >
              {loading ? 'Loading...' : 'Get Greeting'}
            </button>
          </form>
        ) : (
          !auth.isLoading && (
            <button type="button" className={styles.button} onClick={() => void auth.signinRedirect()}>
              Sign in to get a greeting
            </button>
          )
        )}

        {greeting && auth.isAuthenticated && (
          <div className={styles.greeting}>
            <strong>{greeting}</strong>
          </div>
        )}

        {error && (
          <div className={styles.error}>
            Error: {error}
          </div>
        )}

        {auth.error && (
          <div className={styles.error}>
            Sign-in error: {auth.error.message} (is Keycloak running on port 8180?)
          </div>
        )}

        <div className={styles.visits}>
          {visitsError
            ? 'Visit counter unavailable (is the visitor backend running?)'
            : visits === null
              ? 'Counting visits...'
              : <>Visits: <strong>{visits}</strong></>}
        </div>

        <div className={styles.info}>
          <p>Sign-in uses Keycloak (OpenID Connect, Authorization Code + PKCE); <code>/api/hello</code> needs the access token</p>
          <p>The visit counter is served by the visitor backend at <code>/api/visits</code> and stored in Valkey</p>
        </div>
      </div>
    </div>
  )
}

export default App
