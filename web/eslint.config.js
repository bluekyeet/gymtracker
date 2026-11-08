import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import tsParser from '@typescript-eslint/parser';
import svelteParser from 'svelte-eslint-parser';

// TypeScript already reports undefined names (including browser globals and DOM types), and the base
// rules cannot tell a type-only name from a variable, so both are handled by the TypeScript-aware
// versions for .ts and .svelte files.
const typescriptRules = {
  'no-undef': 'off',
  'no-unused-vars': 'off',
  '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }]
};

export default [
  {
    ignores: ['.svelte-kit/**', 'node_modules/**', 'build/**']
  },
  js.configs.recommended,
  ...svelte.configs['flat/recommended'],
  {
    files: ['**/*.ts'],
    plugins: { '@typescript-eslint': tsPlugin },
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 2022,
      sourceType: 'module'
    },
    rules: typescriptRules
  },
  {
    files: ['**/*.svelte'],
    plugins: { '@typescript-eslint': tsPlugin },
    languageOptions: {
      parser: svelteParser,
      parserOptions: {
        parser: tsParser
      }
    },
    rules: typescriptRules
  }
];
