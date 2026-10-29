/**
 * T14 — Tests unitaires sur le store auth.
 *
 * Couvre les invariants critiques sécurité/intégrité :
 *  - Impossible de désactiver / rétrograder / supprimer le dernier ADMIN actif.
 *  - Impossible de supprimer son propre compte connecté.
 *  - Email unique à l'update.
 *  - setPassword refusé si LAN non joignable (interdiction M7).
 *
 * Tests purs : on mock pushMutation + lanReachable + crypto pour isoler la
 * logique du store de toute infra réseau/Web Crypto.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

// ──── Mocks ─────────────────────────────────────────────────────────────────
// Mocker AVANT l'import du store (les modules sont résolus à l'évaluation).
// useBackend.getState mocké en vi.fn pour pouvoir mockReturnValueOnce (test offline)
const mockBackendState = { lanReachable: true };
vi.mock("@/store/backend", () => ({
  useBackend: { getState: vi.fn(() => mockBackendState) },
  pushMutation: vi.fn().mockResolvedValue({ queued: false }),
}));
vi.mock("@/lib/syncBackend", () => ({ hydrateFromBackend: vi.fn() }));
vi.mock("@/lib/sync", () => ({ broadcastSync: vi.fn() }));
vi.mock("@/lib/apiClient", () => ({
  api: vi.fn(),
  tokenStore: { get: () => null, set: vi.fn() },
}));
vi.mock("@/lib/crypto", () => ({
  hashPassword: vi.fn(async (p: string) => `mockhash:${p}`),
  verifyPassword: vi.fn(async () => true),
}));

import { useAuth, type User } from "@/store/auth";
import { useBackend } from "@/store/backend";

const baseAdmin: User = { id: "a1", name: "Admin", email: "admin@x.com", role: "ADMIN", storeId: null, active: true };
const otherAdmin: User = { id: "a2", name: "Admin 2", email: "admin2@x.com", role: "ADMIN", storeId: null, active: true };
const employee: User = { id: "e1", name: "Sarah", email: "sarah@x.com", role: "EMPLOYEE", storeId: "s1", active: true };

function reset(users: User[], me: User | null = null) {
  useAuth.setState({ user: me, users, passwords: {}, attempts: {} }, false);
}

describe("useAuth.toggleUser", () => {
  beforeEach(() => reset([baseAdmin, employee]));

  it("refuse de désactiver le dernier admin actif", () => {
    const res = useAuth.getState().toggleUser("a1");
    expect(res.ok).toBe(false);
    expect(res.error).toContain("dernier admin");
    expect(useAuth.getState().users.find((u) => u.id === "a1")?.active).toBe(true);
  });

  it("autorise la désactivation s'il y a un autre admin actif", () => {
    reset([baseAdmin, otherAdmin, employee]);
    const res = useAuth.getState().toggleUser("a1");
    expect(res.ok).toBe(true);
    expect(useAuth.getState().users.find((u) => u.id === "a1")?.active).toBe(false);
  });

  it("refuse si user inconnu", () => {
    const res = useAuth.getState().toggleUser("nope");
    expect(res.ok).toBe(false);
    expect(res.error).toContain("introuvable");
  });
});

describe("useAuth.updateUser", () => {
  beforeEach(() => reset([baseAdmin, otherAdmin, employee]));

  it("refuse l'email déjà utilisé par un autre user", () => {
    const res = useAuth.getState().updateUser("e1", { email: "admin@x.com" });
    expect(res.ok).toBe(false);
    expect(res.error).toContain("déjà utilisé");
  });

  it("autorise un changement d'email vers un email libre + le persiste en lowercase", () => {
    const res = useAuth.getState().updateUser("e1", { email: "SARAH-NEW@X.COM" });
    expect(res.ok).toBe(true);
    expect(useAuth.getState().users.find((u) => u.id === "e1")?.email).toBe("sarah-new@x.com");
  });

  it("refuse de rétrograder le dernier admin actif", () => {
    reset([baseAdmin, employee]);   // un seul ADMIN actif
    const res = useAuth.getState().updateUser("a1", { role: "EMPLOYEE" });
    expect(res.ok).toBe(false);
    expect(res.error).toContain("rétrograder");
    expect(useAuth.getState().users.find((u) => u.id === "a1")?.role).toBe("ADMIN");
  });

  it("déplace le password si l'email change", () => {
    useAuth.setState({ passwords: { "sarah@x.com": "h1" } }, false);
    useAuth.getState().updateUser("e1", { email: "sarah2@x.com" });
    const pwds = useAuth.getState().passwords;
    expect(pwds["sarah@x.com"]).toBeUndefined();
    expect(pwds["sarah2@x.com"]).toBe("h1");
  });
});

describe("useAuth.deleteUser", () => {
  beforeEach(() => reset([baseAdmin, otherAdmin, employee]));

  it("refuse de supprimer le dernier admin", () => {
    reset([baseAdmin, employee]);
    const res = useAuth.getState().deleteUser("a1");
    expect(res.ok).toBe(false);
    expect(res.error).toContain("dernier admin");
  });

  it("refuse de supprimer son propre compte connecté", () => {
    reset([baseAdmin, otherAdmin], baseAdmin);
    const res = useAuth.getState().deleteUser("a1");
    expect(res.ok).toBe(false);
    expect(res.error).toContain("propre compte");
  });

  it("supprime le user ET son password", () => {
    useAuth.setState({ passwords: { "sarah@x.com": "h1", "admin@x.com": "h2" } }, false);
    const res = useAuth.getState().deleteUser("e1");
    expect(res.ok).toBe(true);
    expect(useAuth.getState().users.find((u) => u.id === "e1")).toBeUndefined();
    expect(useAuth.getState().passwords["sarah@x.com"]).toBeUndefined();
    // Les autres passwords intacts
    expect(useAuth.getState().passwords["admin@x.com"]).toBe("h2");
  });
});

describe("useAuth.setPassword", () => {
  beforeEach(() => reset([baseAdmin, otherAdmin]));

  it("rejette en offline (M7)", async () => {
    vi.mocked(useBackend.getState).mockReturnValueOnce({ lanReachable: false } as unknown as ReturnType<typeof useBackend.getState>);
    await expect(useAuth.getState().setPassword("admin@x.com", "newpw"))
      .rejects.toThrow(/connexion serveur requise/i);
  });

  it("hashe et stocke en online", async () => {
    await useAuth.getState().setPassword("admin@x.com", "newpw");
    expect(useAuth.getState().passwords["admin@x.com"]).toBe("mockhash:newpw");
  });

  it("normalise l'email en lowercase", async () => {
    await useAuth.getState().setPassword("ADMIN@X.COM", "newpw");
    expect(useAuth.getState().passwords["admin@x.com"]).toBeDefined();
    expect(useAuth.getState().passwords["ADMIN@X.COM"]).toBeUndefined();
  });
});
