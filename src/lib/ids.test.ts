import { describe, it, expect, vi, afterEach } from "vitest";
import { newId } from "./ids";

describe("newId", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("préfixe correctement l'ID", () => {
    const id = newId("foo-");
    expect(id.startsWith("foo-")).toBe(true);
  });

  it("génère 1000 IDs uniques sans collision (path nominal crypto.randomUUID)", () => {
    const ids = new Set<string>();
    for (let i = 0; i < 1000; i++) ids.add(newId("p-"));
    expect(ids.size).toBe(1000);
  });

  it("ne collisionne pas dans une boucle synchrone (régression bulkImportProducts)", () => {
    // Bug d'origine : tous les IDs partageaient le même Date.now() →
    // p${now}-${i} → IDs prévisibles + collision si 2 imports même ms.
    const ids = Array.from({ length: 500 }, () => newId("p-"));
    expect(new Set(ids).size).toBe(500);
  });

  it("fallback crypto.getRandomValues quand randomUUID absent (cas HTTP LAN)", () => {
    // Simule un navigateur sans randomUUID mais avec getRandomValues
    // (cas typique HTTP sur IP LAN 192.168.x.x).
    const originalRandomUUID = crypto.randomUUID;
    // @ts-expect-error — override délibéré pour le test
    crypto.randomUUID = undefined;
    try {
      const id = newId("fb-");
      // 16 octets hex = 32 chars
      expect(id).toMatch(/^fb-[0-9a-f]{32}$/);
      // Doit rester unique sur plusieurs appels
      const ids = new Set(Array.from({ length: 100 }, () => newId("fb-")));
      expect(ids.size).toBe(100);
    } finally {
      crypto.randomUUID = originalRandomUUID;
    }
  });

  it("ultimate fallback Date.now+Math.random si Web Crypto absent", () => {
    // Simule un environnement préhistorique sans crypto du tout.
    const originalCrypto = globalThis.crypto;
    // @ts-expect-error — override délibéré pour le test
    delete globalThis.crypto;
    try {
      const id = newId("u-");
      expect(id.startsWith("u-")).toBe(true);
      // Format attendu : u-<digits>-<base36>
      expect(id).toMatch(/^u-\d+-[a-z0-9]+$/);
    } finally {
      globalThis.crypto = originalCrypto;
    }
  });
});
