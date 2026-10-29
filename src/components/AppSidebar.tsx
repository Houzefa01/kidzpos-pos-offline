import {
  LayoutDashboard,
  ShoppingCart,
  Package,
  Receipt,
  Users,
  UsersRound,
  Settings as SettingsIcon,
  Store as StoreIcon,
  LogOut,
} from "lucide-react";
import { BrandMark } from "@/components/ds";
import { NavLink } from "@/components/NavLink";
import { useNavigate } from "react-router-dom";
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarHeader,
  SidebarFooter,
  useSidebar,
} from "@/components/ui/sidebar";
import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { Button } from "@/components/ui/button";
import { preloadRoute } from "@/lib/preload";
import { useEffect } from "react";

const adminItems = [
  { title: "Dashboard", url: "/", icon: LayoutDashboard },
  { title: "Caisse (POS)", url: "/pos", icon: ShoppingCart },
  { title: "Stock & Produits", url: "/stock", icon: Package },
  { title: "Ventes", url: "/sales", icon: Receipt },
  { title: "Clients", url: "/customers", icon: UsersRound },
  { title: "Utilisateurs", url: "/users", icon: Users },
  { title: "Paramètres", url: "/settings", icon: SettingsIcon },
];

const employeeItems = [
  { title: "Caisse (POS)", url: "/pos", icon: ShoppingCart },
  { title: "Mon Stock", url: "/stock", icon: Package },
  { title: "Mes Ventes", url: "/sales", icon: Receipt },
  { title: "Clients", url: "/customers", icon: UsersRound },
];

export function AppSidebar() {
  const { state, isMobile, setOpenMobile } = useSidebar();
  const collapsed = state === "collapsed";
  const { user, logout } = useAuth();
  const { stores } = useData();
  const navigate = useNavigate();

  // Mobile/tablette : auto-close du sheet sidebar au clic d'un lien.
  // Sur desktop (isMobile=false), aucun effet — le sidebar reste comme il est.
  const closeMobileSidebar = () => { if (isMobile) setOpenMobile(false); };

  // Précharge toutes les pages accessibles, en idle, après l'auth.
  useEffect(() => {
    if (!user) return;
    type RIC = (cb: IdleRequestCallback, opts?: IdleRequestOptions) => number;
    const idle = (cb: () => void) =>
      "requestIdleCallback" in window
        ? (window as Window & { requestIdleCallback: RIC }).requestIdleCallback(cb, { timeout: 2000 })
        : setTimeout(cb, 800);
    const allowed = user.role === "ADMIN" ? adminItems : employeeItems;
    idle(() => allowed.forEach((i) => preloadRoute(i.url)));
  }, [user]);

  if (!user) return null;
  const items = user.role === "ADMIN" ? adminItems : employeeItems;
  const currentStore = stores.find((s) => s.id === user.storeId);

  const handleLogout = () => {
    closeMobileSidebar();
    logout();
    navigate("/login");
  };

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="border-b border-sidebar-border">
        <div className="flex items-center gap-2.5 px-2 py-3.5">
          <BrandMark />
          {!collapsed && (
            <div className="flex flex-col leading-tight">
              <span className="text-sm font-semibold tracking-tight">KidzPOS</span>
              <span className="text-2xs uppercase tracking-eyebrow text-muted-foreground">
                Caisse &amp; gestion
              </span>
            </div>
          )}
        </div>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground/80">
            {user.role === "ADMIN" ? "Administration" : "Espace caissier"}
          </SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {items.map((item) => {
                const showBadge = item.url === "/stock";
                const lowCount = showBadge
                  ? (user.role === "ADMIN" ? useData.getState().products : useData.getState().products.filter((p) => p.storeId === user.storeId)).filter((p) => p.stock <= 3).length
                  : 0;
                return (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton asChild>
                      <NavLink
                        to={item.url}
                        end={item.url === "/"}
                        onMouseEnter={() => preloadRoute(item.url)}
                        onFocus={() => preloadRoute(item.url)}
                        onClick={closeMobileSidebar}
                        className="group relative flex items-center gap-3 rounded-xl px-2.5 py-2 text-sm text-sidebar-foreground transition-all hover:bg-sidebar-accent hover:translate-x-0.5"
                        activeClassName="!bg-sidebar-accent !text-sidebar-accent-foreground font-semibold before:absolute before:left-0 before:top-1.5 before:bottom-1.5 before:w-[3px] before:rounded-r-full before:bg-gradient-to-b before:from-primary before:to-accent"
                      >
                        <item.icon className="h-4 w-4 shrink-0 transition-transform group-hover:scale-110" />
                        {!collapsed && <span className="flex-1">{item.title}</span>}
                        {!collapsed && showBadge && lowCount > 0 && (
                          <span className="rounded-full bg-warning/25 px-2 py-0.5 font-mono text-2xs font-bold text-warning-foreground">
                            {lowCount}
                          </span>
                        )}
                      </NavLink>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {!collapsed && currentStore && (
          <SidebarGroup>
            <SidebarGroupLabel className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground/80">
              Magasin
            </SidebarGroupLabel>
            <SidebarGroupContent>
              <div className="mx-2 rounded-lg border border-sidebar-border bg-card p-3">
                <div className="flex items-start gap-2.5">
                  <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <StoreIcon className="h-3.5 w-3.5" />
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold leading-tight tracking-tight">
                      {currentStore.name}
                    </p>
                    <p className="mt-0.5 text-label text-muted-foreground">
                      {currentStore.location}
                    </p>
                  </div>
                </div>
              </div>
            </SidebarGroupContent>
          </SidebarGroup>
        )}
      </SidebarContent>

      <SidebarFooter className="border-t border-sidebar-border p-2">
        {!collapsed ? (
          <div className="space-y-2">
            <div className="flex items-center gap-2.5 px-2 py-1">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-gradient-to-br from-primary to-accent text-sm font-semibold text-primary-foreground">
                {user.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold leading-tight">{user.name}</p>
                <p className="text-label text-muted-foreground">
                  {user.role === "ADMIN" ? "Administrateur" : "Caissier"}
                </p>
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start rounded-lg text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
              onClick={handleLogout}
            >
              <LogOut className="mr-2 h-4 w-4" /> Déconnexion
            </Button>
          </div>
        ) : (
          <Button variant="ghost" size="icon" onClick={handleLogout} className="hover:text-destructive">
            <LogOut className="h-4 w-4" />
          </Button>
        )}
      </SidebarFooter>
    </Sidebar>
  );
}
