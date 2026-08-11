'use strict'

/**
 * Local ESLint plugin that exposes project-specific lint rules.
 * Registered as `eslint-plugin-local-rules` via the `local-rules` plugin alias in .eslintrc.cjs.
 */
module.exports = {
  rules: {
    'no-hardcoded-visual-literals': require('./no-hardcoded-visual-literals'),
  },
}
