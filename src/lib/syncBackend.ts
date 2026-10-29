import { api, apiWithEtag, refreshAccessToken, tokenStore, ApiError } from "@/lib/apiClient";
import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { useSales } from "@/store/sales";
import { useCustomers } from "@/store/customers";
import { useSettings } from "@/store/settings";
import { useBackend } from "@/store/backend";
import { startSse } from "@/lib/sse";
import {
  StoreSchema,
  ProductSchema,
  SaleSchema,
  CustomerSchema,
  SettingsSchema,
  UserSchema,
} from "@/lib/schemas";
import { z, ZodSchema } from "zod";

let _hydrating = false;
let _needsRehydrate = false;

/**
 * P2.1 — Résultat unitaire d'une ressource hydratée.
 *
 *   { ok: true,  data }    → fetch + parse réussis, à appliquer au store
 *   { ok: false, status }  → 401/403 → on remonte pour gérer session
 *   { ok: false, error }   → erreur réseau, parse Zod, ou 5xx → on log + on skip
 */
type FetchResult<T> =
  | { ok: true; data: T }
  | { ok: true; notModified: true }    // 304 : serveur dit "rien changé", on garde l'état
  | { ok: false; status?: number; error: string };

async function fetchAndParse<T>(path: string, schema: ZodSchema<T>): Promise<FetchResult<T>> {
  try {
    const raw = await api<unknown>(path);
    const data = schema.parse(raw);
    return { ok: true, data };
  } catch (e: unknown) {
    if (e instanceof ApiError) {
      return { ok: false, status: e.status, error: e.message };
    }
    const message = e instanceof Error ? e.message : "Erreur inconnue";
    return { ok: false, error: message };
  }
}

// PR http-hardening — ETag cache pour /api/products (cf ProductController.list).
// ETag persistant entre appels au sein d'une session : si rien n'a changé
// côté backend, le serveur renvoie 304 sans body → on skip l'écrasement de
// useData.products et on économise le payload.
//
// Stockage en module : volatile (perdu au refresh, OK car syncBackend re-hydrate
// au boot de toute façon). Pas de besoin de persister.
let productsEtag: string | null = null;

async function fetchAndParseWithEtag<T>(path: string, schema: ZodSchema<T>): Promise<FetchResult<T>> {
  try {
    const res = await apiWithEtag<unknown>(path, productsEtag ?? undefined);
    if (res.etag) productsEtag = res.etag;
    if (res.notModified) {
      return { ok: true, notModified: true };
    }
    const data = schema.parse(res.data);
    return { ok: true, data };
  } catch (e: unknown) {
    if (e instanceof ApiError) {
      // Sur 404/410 (rare), invalider le cache pour ne pas boucler sur un ETag mort
      if (e.status === 404 || e.status === 410) productsEtag = null;
      return { ok: false, status: e.status, error: e.message };
    }
    const message = e instanceof Error ? e.message : "Erreur inconnue";
    return { ok: false, error: message };
  }
}

/**
 * P2.1 — Hydratation TOLÉRANTE aux pannes partielles.
 *
 *  - Promise.allSettled : aucune ressource ne fait tomber les autres.
 *  - Chaque ressource est appliquée indépendamment à son store.
 *  - 401/403 sur ≥1 ressource → session morte, on remonte une erreur dédiée.
 *  - lanReachable ne bascule à `false` que si TOUTES les ressources critiques
 *    ont échoué (signe d'un backend réellement injoignable, pas d'un endpoint
 *    isolé en 500).
 *  - Log explicite par endpoint échoué (debuggable en prod via console).
 */
export async function hydrateFromBackend(): Promise<{ ok: boolean; error?: string }> {
  // Si l'access token est absent (boot après reload, ou expiration silencieuse),
  // tenter un refresh via le cookie httpOnly avant de bailer.
  if (!tokenStore.get()) {
    const refreshed = await refreshAccessToken();
    if (!refreshed) return { ok: false, error: "Non authentifié" };
  }
  if (_hydrating) {
    _needsRehydrate = true;
    return { ok: false, error: "Hydratation déjà en cours — re-planifiée" };
  }
  _hydrating = true;
  try {
    const user = useAuth.getState().user;
    const role = user?.role;

    // P4 — Politique GET cross-store STRICTE côté backend : un EMPLOYEE doit
    // expliciter `?storeId=` sur les endpoints store-scopés (products, sales).
    // Sans ce param, le backend retourne 403 (anti-leak cross-store).
    // ADMIN passe partout → on omet le param pour garder la sémantique "tous".
    const storeScope = role === "EMPLOYEE" && user?.storeId
      ? `?storeId=${encodeURIComponent(user.storeId)}`
      : "";

    // P2.1 : Promise.allSettled — toutes les ressources tentent en parallèle,
    // aucune ne fait tomber l'ensemble.
    // PR http-hardening : /api/products passe par fetchAndParseWithEtag pour
    // bénéficier du 304 Not Modified si le catalogue n'a pas bougé.
    const [storesR, productsR, salesR, customersR, settingsR, usersR] = await Promise.all([
      fetchAndParse("/api/stores", z.array(StoreSchema)),
      fetchAndParseWithEtag(`/api/products${storeScope}`, z.array(ProductSchema)),
      fetchAndParse(`/api/sales${storeScope}`, z.array(SaleSchema)),
      fetchAndParse("/api/customers", z.array(CustomerSchema)),
      fetchAndParse("/api/settings", SettingsSchema),
      role === "ADMIN"
        ? fetchAndParse("/api/users", z.array(UserSchema))
        : Promise.resolve<FetchResult<null>>({ ok: true, data: null }),
    ]);

    // Session morte si AU MOINS UNE ressource auth-required renvoie 401/403.
    // (Le backend a invalidé le token → inutile de continuer à hydrater partiellement.)
    const authFailures = [storesR, productsR, salesR, customersR, settingsR, usersR]
      .filter((r): r is { ok: false; status: number; error: string } =>
        !r.ok && (r.status === 401 || r.status === 403));
    if (authFailures.length > 0) {
      tokenStore.set(null);
      return { ok: false, error: "Session expirée" };
    }

    // Application indépendante : chaque store reçoit ses données ssi le fetch est OK
    // ET que le serveur a renvoyé des données fraîches (pas un 304 Not Modified).
    // Sur 304 produits, on garde l'état local (useData.products inchangé).
    const productsFresh = productsR.ok && "data" in productsR;
    const productsData = productsFresh ? productsR.data : null;
    if (storesR.ok && productsFresh) {
      useData.setState({ stores: storesR.data, products: productsData! });
    } else if (storesR.ok) {
      useData.setState({ stores: storesR.data });
    } else if (productsFresh) {
      useData.setState({ products: productsData! });
    }

    if (salesR.ok) {
      const saleSeq: Record<string, number> = {};
      for (const s of salesR.data) saleSeq[s.storeId] = Math.max(saleSeq[s.storeId] ?? 0, s.seq ?? 0);
      useSales.getState().setAll(salesR.data, saleSeq);
    }
    if (customersR.ok) useCustomers.setState({ customers: customersR.data });
    if (settingsR.ok) useSettings.setState({ settings: settingsR.data });
    if (usersR.ok && usersR.data !== null) {
      // storeId arrive en `null` côté backend → on convertit en `null` strict pour le type User.
      const users = usersR.data.map((u) => ({ ...u, storeId: u.storeId ?? null }));
      useAuth.setState({ users });
    }

    // Log explicite par ressource échouée (debuggable en prod).
    const failures: Array<{ resource: string; result: FetchResult<unknown> }> = [
      { resource: "stores", result: storesR },
      { resource: "products", result: productsR },
      { resource: "sales", result: salesR },
      { resource: "customers", result: customersR },
      { resource: "settings", result: settingsR },
      { resource: "users", result: usersR },
    ].filter((x) => !x.result.ok);

    for (const f of failures) {
      const r = f.result as { ok: false; status?: number; error: string };
      console.warn(`[hydrate] /${f.resource} a échoué (status=${r.status ?? "n/a"}): ${r.error}`);
    }

    // P2.1 : lanReachable bascule à `false` UNIQUEMENT si TOUTES les ressources
    // critiques ont échoué (vrai signal "backend down"). Sinon le reverse-proxy /
    // backend répond mais a un endpoint cassé — on reste en mode online dégradé.
    const criticalFailed = !storesR.ok && !productsR.ok && !salesR.ok && !customersR.ok && !settingsR.ok;
    if (criticalFailed) {
      useBackend.setState({ lanReachable: false });
      return { ok: false, error: "Toutes les ressources critiques ont échoué" };
    }

    useBackend.setState({ lanReachable: true, lastSync: Date.now() });
    void startSse();
    return { ok: true };
  } finally {
    _hydrating = false;
    // Si un event SSE a tenté d'hydrater pendant qu'on tournait → relancer
    if (_needsRehydrate) {
      _needsRehydrate = false;
      setTimeout(() => hydrateFromBackend().catch(() => {}), 100);
    }
  }
}
