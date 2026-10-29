import { useEffect } from "react";
import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "./AppSidebar";
import { OfflineBanner } from "./OfflineBanner";
import { ThemeToggle } from "./ThemeToggle";
import { Outlet, Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "@/store/auth";
import { tokenStore } from "@/lib/apiClient";
import { toast } from "sonner";

export default function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // Écoute la fin de session (token 401) — redirection via React Router, pas hard reload
  useEffect(() => {
    const handler = () => {
      toast.error("Session expirée — veuillez vous reconnecter");
      logout();
      navigate("/login", { replace: true });
    };
    window.addEventListener("auth:session-expired", handler);
    return () => window.removeEventListener("auth:session-expired", handler);
  }, [logout, navigate]);

  // Vérification initiale : si le token a expiré entre deux sessions
  useEffect(() => {
    if (user && !tokenStore.get()) {
      logout();
      navigate("/login", { replace: true });
    }
  }, [user, logout, navigate]);

  if (!user) return <Navigate to="/login" replace />;

  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full">
        <AppSidebar />
        <div className="flex flex-1 flex-col">
          {/* Banner + header sticky GROUPÉS : sinon banner-top-0 et header-top-0
              se chevaucheraient. Le wrapper sticky pousse l'ensemble au scroll. */}
          <div className="sticky top-0 z-30">
            <OfflineBanner />
            <header className="no-print flex h-14 items-center gap-3 border-b border-border/70 bg-background/70 px-4 backdrop-blur-xl">
              <SidebarTrigger />
              <div className="flex-1" />
              <ThemeToggle />
              <div className="flex min-w-0 items-center gap-2 rounded-full border border-border bg-card px-3 py-1 text-xs text-muted-foreground">
                <span className="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-success" />
                <span className="max-w-[120px] truncate font-medium text-foreground sm:max-w-none">
                  {user.name}
                </span>
                <span className="hidden text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground sm:inline">
                  {user.role === "ADMIN" ? "admin" : "caisse"}
                </span>
              </div>
            </header>
          </div>
          <main className="flex-1 animate-fade-in p-3 sm:p-4 lg:p-6 xl:p-8">
            <Outlet />
          </main>
        </div>
      </div>
    </SidebarProvider>
  );
}

export function RequireRole({ role, children }: { role: "ADMIN" | "EMPLOYEE"; children: React.ReactNode }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== role) return <Navigate to="/" replace />;
  return <>{children}</>;
}
