import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';

export default [
  {
    ignores: ['.svelte-kit/**', 'node_modules/**', 'build/**']
  },
  js.configs.recommended,
  ...svelte.configs['flat/recommended'],
  {
    files: ['**/*.{js,ts}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module'
    }
  }
];
