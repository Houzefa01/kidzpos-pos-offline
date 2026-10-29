// Sync simple multi-onglets via BroadcastChannel.
// Quand un store Zustand persist écrit dans localStorage, on notifie les autres
// onglets pour qu'ils rehydratent.

const CHANNEL = "kidzpos-sync";

export function broadcastSync(key: string) {
  try {
    const bc = new BroadcastChannel(CHANNEL);
    bc.postMessage({ key, ts: Date.now() });
    bc.close();
  } catch {
    // BroadcastChannel non supporté (vieux navigateur) — fallback storage event
  }
}

export function listenSync(onChange: (key: string) => void) {
  const handler = (e: MessageEvent) => onChange(e.data?.key ?? "");
  let bc: BroadcastChannel | null = null;
  try {
    bc = new BroadcastChannel(CHANNEL);
    bc.addEventListener("message", handler);
  } catch (_e) { /* BroadcastChannel not supported — fallback to storage event below */ }
  // Fallback navigateurs anciens
  const storageHandler = (e: StorageEvent) => { if (e.key) onChange(e.key); };
  window.addEventListener("storage", storageHandler);
  return () => {
    if (bc) { bc.removeEventListener("message", handler); bc.close(); }
    window.removeEventListener("storage", storageHandler);
  };
}
