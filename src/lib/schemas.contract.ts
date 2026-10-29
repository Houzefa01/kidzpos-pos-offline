/**
 * Contrat type-level Zod ↔ OpenAPI (types générés).
 *
 * Pour chaque schema Zod listé ici, on vérifie au moment du `tsc` que
 * `z.infer<typeof XSchema>` est assignable au type généré depuis la spec
 * OpenAPI backend. Si un champ Zod diverge du contrat (typo, type incompatible,
 * champ absent côté backend), le typecheck échoue → la dérive FE/BE est
 * attrapée à la build, pas à la prod.
 *
 * Pourquoi ce fichier séparé : on évite de polluer schemas.ts avec des asserts
 * type-level. Ce module n'exporte rien à runtime, il sert uniquement de garde
 * statique. Tree-shaké au build (zéro octet runtime).
 *
 * Pilote sur Sale + Product (les schemas les plus utilisés). Étendre
 * progressivement aux autres au fil des PR.
 *
 * Note sur le sens de l'assertion : on vérifie ZodInfer → ApiType, pas
 * l'inverse. Sémantique : "ce que Zod accepte est compatible avec le contrat
 * OpenAPI". Le sens inverse échoue souvent à cause des `.default()` Zod ou
 * du fait qu'openapi-typescript marque tout en optionnel (limite Jackson).
 */

import type { z } from "zod";
import type { components } from "./api-types";
import { SaleSchema, ProductSchema, CustomerSchema, SettingsSchema } from "./schemas";

// ──── Sale ──────────────────────────────────────────────────────────────────
type ApiSale = components["schemas"]["Sale"];
type ZodSale = z.infer<typeof SaleSchema>;
// L'assignation typée déclenche le check à la compilation : si ZodSale n'est
// pas assignable à ApiSale, tsc lève. La fonction n'est jamais appelée.
const _saleContract: (s: ZodSale) => ApiSale = (s) => s;
void _saleContract;

// ──── Product ───────────────────────────────────────────────────────────────
type ApiProduct = components["schemas"]["Product"];
type ZodProduct = z.infer<typeof ProductSchema>;
const _productContract: (p: ZodProduct) => ApiProduct = (p) => p;
void _productContract;

// ──── Customer ──────────────────────────────────────────────────────────────
type ApiCustomer = components["schemas"]["Customer"];
type ZodCustomer = z.infer<typeof CustomerSchema>;
const _customerContract: (c: ZodCustomer) => ApiCustomer = (c) => c;
void _customerContract;

// ──── Settings ──────────────────────────────────────────────────────────────
type ApiSettings = components["schemas"]["Settings"];
type ZodSettings = z.infer<typeof SettingsSchema>;
const _settingsContract: (s: ZodSettings) => ApiSettings = (s) => s;
void _settingsContract;
