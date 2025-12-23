import { useState } from 'react'
import styles from './App.module.css'

function App() {
  const [name, setName] = useState('')
  const [greeting, setGreeting] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const fetchGreeting = async () => {
    setLoading(true)
    setError('')

    try {
      const response = await fetch(`/api/hello${name ? `?name=${encodeURIComponent(name)}` : ''}`)

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

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <h1 className={styles.title}>Spring Boot + React</h1>
        <p className={styles.subtitle}>Full-stack web application</p>

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
              placeholder="World"
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

        {greeting && (
          <div className={styles.greeting}>
            <strong>{greeting}</strong>
          </div>
        )}

        {error && (
          <div className={styles.error}>
            Error: {error}
          </div>
        )}

        <div className={styles.info}>
          <p>This React frontend calls the Spring Boot backend API at <code>/api/hello</code></p>
        </div>
      </div>
    </div>
  )
}

export default App
