import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App.jsx';
import './styles/index.css';
import { resolveFromMirror } from './appearance/resolveAppearance.js';

// Pre-paint: set data-appearance before React hydrates so no flash occurs for
// users with a stored dark/system preference. Runs synchronously from this
// hashed ES module, which is CSP-safe without unsafe-inline.
document.documentElement.setAttribute('data-appearance', resolveFromMirror());

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
