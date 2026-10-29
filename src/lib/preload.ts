// Map d'imports dynamiques pour préchargement au survol/après login.
// Vite réutilisera le module en cache lorsque React.lazy le demandera.
export const routePreloaders: Record<string, () => Promise<unknown>> = {
  "/": () => import("@/pages/Index"),
  "/pos": () => import("@/pages/POS"),
  "/stock": () => import("@/pages/Stock"),
  "/sales": () => import("@/pages/Sales"),
  "/users": () => import("@/pages/Users"),
  "/customers": () => import("@/pages/Customers"),
  "/settings": () => import("@/pages/Settings"),
};

export function preloadRoute(path: string) {
  const fn = routePreloaders[path];
  if (fn) fn().catch(() => {});
}
