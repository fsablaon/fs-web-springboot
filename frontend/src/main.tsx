import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import './index.css'
import App from './App.tsx'
import styles from './App.module.css'
import { loadAuthConfig } from './auth/authConfig.ts'

const root = createRoot(document.getElementById('root')!)

// The sign-in settings come from the facade (GET /api/auth/config) before the app renders
loadAuthConfig()
  .then((authConfig) => {
    root.render(
      <StrictMode>
        <AuthProvider {...authConfig}>
          <App />
        </AuthProvider>
      </StrictMode>,
    )
  })
  .catch((err: unknown) => {
    root.render(
      <StrictMode>
        <div className={styles.container}>
          <div className={styles.card}>
            <h1 className={styles.title}>Spring Boot + React</h1>
            <div className={styles.error}>
              {err instanceof Error ? err.message : 'Cannot load sign-in settings'}
            </div>
          </div>
        </div>
      </StrictMode>,
    )
  })
