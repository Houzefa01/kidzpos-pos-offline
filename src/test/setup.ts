import "@testing-library/jest-dom";

// jsdom + Node 22 expérimental : localStorage absent au module-init des modules
// qui le lisent (apiConfig.ts, stores Zustand persistés). Shim léger ici pour
// permettre l'import de toute la chaîne sans toucher au code applicatif.
if (typeof globalThis.localStorage === "undefined") {
  const store = new Map<string, string>();
  const memoryStorage: Storage = {
    get length() { return store.size; },
    clear: () => store.clear(),
    getItem: (k) => store.get(k) ?? null,
    key: (i) => Array.from(store.keys())[i] ?? null,
    removeItem: (k) => { store.delete(k); },
    setItem: (k, v) => { store.set(k, String(v)); },
  };
  Object.defineProperty(globalThis, "localStorage", { value: memoryStorage, writable: true });
  Object.defineProperty(globalThis, "sessionStorage", { value: memoryStorage, writable: true });
}

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => {},
  }),
});
