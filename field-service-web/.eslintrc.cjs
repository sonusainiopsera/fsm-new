'use strict';

module.exports = {
  root: true,
  env: {
    browser: true,
    es2020: true,
    node: true,
  },
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:import/recommended',
  ],
  plugins: ['react', 'react-hooks', 'jsx-a11y', 'import'],
  settings: {
    react: { version: '18' },
    'import/resolver': { node: { extensions: ['.js', '.jsx'] } },
  },
  parserOptions: {
    ecmaVersion: 2020,
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  rules: {
    // Custom local rule — loaded via --rulesdir eslint-local-rules
    'no-hardcoded-visual-literals': 'error',

    // React
    'react/prop-types': 'warn',
    'react/display-name': 'warn',

    // Import ordering
    'import/order': ['warn', { 'newlines-between': 'always' }],

    // General quality
    'no-console': ['warn', { allow: ['warn', 'error'] }],
    'no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    eqeqeq: ['error', 'always'],
  },
  overrides: [
    {
      // Test files may use node built-ins freely
      files: ['**/__tests__/**', '**/*.test.{js,jsx}', 'scripts/**'],
      rules: {
        'no-console': 'off',
      },
    },
    {
      // Security control: web storage is forbidden in auth and token-handling
      // modules so no code path can write the access token to a persistent store.
      // appearanceMirror.js intentionally uses localStorage for appearance only
      // and is explicitly excluded.
      files: [
        'src/surfaces/auth/**/*.{js,jsx}',
        'src/api/tokenStore.js',
        'src/api/http.js',
        'src/app/AuthContext.js',
      ],
      excludedFiles: ['**/__tests__/**', '**/*.test.{js,jsx}'],
      rules: {
        'no-restricted-globals': [
          'error',
          {
            name: 'localStorage',
            message: 'Store tokens in tokenStore (src/api/tokenStore.js), never in web storage.',
          },
          {
            name: 'sessionStorage',
            message: 'Store tokens in tokenStore (src/api/tokenStore.js), never in web storage.',
          },
        ],
        'no-restricted-properties': [
          'error',
          {
            object: 'window',
            property: 'localStorage',
            message: 'Store tokens in tokenStore (src/api/tokenStore.js), never in web storage.',
          },
          {
            object: 'window',
            property: 'sessionStorage',
            message: 'Store tokens in tokenStore (src/api/tokenStore.js), never in web storage.',
          },
        ],
      },
    },
  ],
};
