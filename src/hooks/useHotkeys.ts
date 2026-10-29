import { useEffect } from "react";

type Combo = string; // ex: "F2", "F9", "Escape", "ctrl+k"
type Handler = (e: KeyboardEvent) => void;

export function useHotkeys(map: Record<Combo, Handler>) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const parts: string[] = [];
      if (e.ctrlKey || e.metaKey) parts.push("ctrl");
      if (e.shiftKey) parts.push("shift");
      if (e.altKey) parts.push("alt");
      parts.push(e.key);
      const key = parts.join("+").toLowerCase();
      const direct = e.key.toLowerCase();
      const handler = map[key] ?? map[direct] ?? map[e.key];
      if (handler) {
        handler(e);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [map]);
}
