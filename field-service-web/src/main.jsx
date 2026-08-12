import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App.jsx'

async function enableMocking() {
  // Only enable MSW in development to avoid shipping mocks in production
  if (import.meta.env.DEV) {
    const { worker } = await import('./mocks/browser.js')
    return worker.start({
      onUnhandledRequest: 'bypass',
    })
  }
}

enableMocking().then(() => {
  const root = document.getElementById('root')
  if (!root) {
    throw new Error('Root element #root not found in the document')
  }

  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>
  )
})
