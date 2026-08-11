'use strict';

module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true,
  },
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  plugins: ['react', 'react-hooks', 'jsx-a11y', 'import'],
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:import/recommended',
  ],
  settings: {
    react: { version: 'detect' },
  },
  rules: {
    // Custom local rule enforced via --rulesdir eslint-local-rules (Q6 compensating control)
    'no-hardcoded-visual-literals': 'error',
    'react/react-in-jsx-scope': 'off',
    'react/prop-types': 'off',
    'import/order': ['warn', { alphabetize: { order: 'asc' } }],
    'no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
  },
  overrides: [
    {
      files: ['**/__tests__/**', '**/*.test.js', '**/*.test.jsx'],
      env: { jest: true },
      rules: {
        'no-hardcoded-visual-literals': 'off',
      },
    },
    {
      files: ['scripts/**/*.mjs'],
      env: { node: true },
      parserOptions: { sourceType: 'module' },
    },
  ],
};
