const toHex = (arr: Uint8Array): string =>
  Array.from(arr).map((b) => b.toString(16).padStart(2, "0")).join("");

/** Retourne null si crypto.subtle n'est pas disponible (HTTP non-localhost). */
export async function hashPassword(password: string, salt?: string): Promise<string | null> {
  if (!crypto?.subtle) return null;
  const s = salt ?? toHex(crypto.getRandomValues(new Uint8Array(8)));
  const enc = new TextEncoder();
  const data = enc.encode(`${s}::${password}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  const buf = toHex(new Uint8Array(digest));
  return `${s}$${buf}`;
}

/**
 * Vérifie un mot de passe contre son hash stocké.
 * Retourne false si crypto.subtle est absent (contexte HTTP) — l'auth offline
 * sera indisponible, mais l'auth backend reste fonctionnelle.
 */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  if (!stored || !stored.includes("$") || !crypto?.subtle) return false;
  const [salt] = stored.split("$");
  const candidate = await hashPassword(password, salt);
  return candidate === stored;
}
