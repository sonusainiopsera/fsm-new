import React from 'react'
import { createRoot } from 'react-dom/client'

import './styles/index.css'

/**
 * Application entry point.
 * Loads the design token stylesheet (tokens.contract → tokens.light → tokens.dark → base).
 * The active appearance is controlled by setting data-appearance="dark" on <html>.
 */
function App() {
  return (
    <div className="content-container">
      <h1>Field Service Platform</h1>
    </div>
  )
}

const root = createRoot(document.getElementById('root'))
root.render(<App />)
