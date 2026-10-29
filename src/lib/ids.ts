/**
 * P2.3 — Générateur d'IDs côté client.
 *
 * Pourquoi pas Date.now() seul : collisions garanties si > 1 enqueue/ms
 * (typique : bulkImportProducts crée N produits dans la même boucle synchrone,
 * tous avec le même Date.now() → même ID si seul horodatage).
 *
 * Stratégie en cascade :
 *
 *   1. crypto.randomUUID()        → UUID v4 RFC 4122. Disponible en
 *                                    "secure context" (HTTPS, ou HTTP sur
 *                                    localhost). 122 bits d'entropie.
 *
 *   2. crypto.getRandomValues()   → 128 bits d'entropie crypto. Disponible
 *                                    PARTOUT (HTTP non-localhost inclus :
 *                                    LAN 192.168.x, 10.x, 172.16-31.x).
 *                                    C'est le path nominal pour le déploiement
 *                                    KidzPOS LAN HTTP sans Caddy.
 *
 *   3. Date.now() + Math.random() → filet de sécurité (très vieux navigateur
 *                                    sans Web Crypto API — IE, Edge < 12).
 *                                    Non-cryptographique mais 10 chars base36
 *                                    = 36¹⁰ ≈ 3.6×10¹⁵ combinaisons/ms.
 *                                    Collision pratiquement nulle pour KidzPOS.
 *
 * Conclusion : en pratique, sur LAN HTTP (cas réel de production), on tape sur
 * le chemin (2) qui est cryptographiquement sûr. Le fallback Math.random ne
 * sert qu'aux navigateurs préhistoriques que KidzPOS ne supporte pas.
 */
export function newId(prefix: string): string {
  if (typeof crypto !== "undefined") {
    if (typeof crypto.randomUUID === "function") {
      return `${prefix}${crypto.randomUUID()}`;
    }
    if (typeof crypto.getRandomValues === "function") {
      const buf = new Uint8Array(16);
      crypto.getRandomValues(buf);
      let hex = "";
      for (let i = 0; i < buf.length; i++) hex += buf[i].toString(16).padStart(2, "0");
      return `${prefix}${hex}`;
    }
  }
  return `${prefix}${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}
