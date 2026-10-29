import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules", "backend"] },
  {
    // P3.1 : `recommended` non-typed + activation CIBLÉE de la règle demandée.
    // On évite `recommendedTypeChecked` qui apporte un bruit hors scope
    // (require-await, unbound-method, prefer-const) sans valeur pour cette PR.
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["src/**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
      parserOptions: {
        // Type-aware nécessaire pour que no-floating-promises sache reconnaître
        // les Promise<T>. `projectService: true` = lookup automatique du tsconfig.
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      "@typescript-eslint/no-unused-vars": "off",
      // P3.1 — garde-fou principal.
      //   ignoreVoid : autorise `void somePromise()` pour fire-and-forget intentionnel
      //                (cf hydrateFromBackend, broadcastSync, startSse côté boot).
      //   ignoreIIFE : autorise `(async () => { ... })()` pour les bootstrap modules.
      "@typescript-eslint/no-floating-promises": ["error", {
        ignoreVoid: true,
        ignoreIIFE: true,
      }],
    },
  },
  {
    // Tests : helpers de mock font souvent du fire-and-forget volontaire,
    // pas d'intérêt à les surveiller.
    files: ["src/**/*.test.{ts,tsx}", "src/test/**/*.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-floating-promises": "off",
    },
  },
);
