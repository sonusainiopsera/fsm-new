'use strict'

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
  plugins: [
    'react',
    'react-hooks',
    'jsx-a11y',
    'import',
    'local-rules',
  ],
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:import/recommended',
  ],
  settings: {
    react: { version: 'detect' },
  },
  rules: {
    'local-rules/no-hardcoded-visual-literals': 'error',
    'import/order': [
      'warn',
      {
        groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
        'newlines-between': 'always',
      },
    ],
    'no-console': ['warn', { allow: ['warn', 'error'] }],
  },
  overrides: [
    {
      files: ['eslint-local-rules/**', 'scripts/**'],
      env: { node: true },
      rules: { 'local-rules/no-hardcoded-visual-literals': 'off' },
    },
    {
      files: ['src/**/*.test.js', 'src/**/*.test.jsx', 'src/test/**'],
      env: { node: true },
      rules: {
        'local-rules/no-hardcoded-visual-literals': 'off',
        'no-console': 'off',
      },
    },
  ],
}
