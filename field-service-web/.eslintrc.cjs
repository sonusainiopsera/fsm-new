module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
  ],
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } },
  settings: { react: { version: 'detect' } },
  rules: {
    // Ban raw hex values and magic spacing numbers in JSX style props
    'no-restricted-syntax': [
      'error',
      {
        selector: 'JSXAttribute[name.name="style"] > JSXExpressionContainer > ObjectExpression > Property > Literal[value=/^#[0-9a-fA-F]{3,8}$/]',
        message: 'Use design tokens instead of raw hex colors',
      },
    ],
  },
}
