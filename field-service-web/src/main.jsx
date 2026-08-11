import React from 'react'
import { createRoot } from 'react-dom/client'

import './styles/index.css'

/**
 * Application entry point.
 * Loads the design token stylesheet (tokens.contract → tokens.light → tokens.dark → base).
 * The active appearance is controlled by setting data-appearance="dark" on <html>.
 *
 * When VITE_CATALOGUE=true the component catalogue is rendered instead of the app shell.
 * This allows CI to build and smoke-check all primitives without a backend.
 */
async function mountApp() {
  let Component

  if (import.meta.env.VITE_CATALOGUE === 'true') {
    const { CatalogueRoute } = await import('./catalogue/CatalogueRoute.jsx')
    Component = CatalogueRoute
  } else {
    const { AppProviders } = await import('./app/AppProviders.jsx')
    Component = AppProviders
  }

  const root = createRoot(document.getElementById('root'))
  root.render(<Component />)
}

mountApp()
