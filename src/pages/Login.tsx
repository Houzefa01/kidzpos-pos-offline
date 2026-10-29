import { useEffect, useState } from "react";
import { preloadRoute } from "@/lib/preload";
import { useNavigate, Navigate } from "react-router-dom";
import { useAuth } from "@/store/auth";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ShieldCheck, ArrowRight, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { Button, BrandMark, StatusDot, PasswordInput } from "@/components/ds";

const demoAccounts = import.meta.env.DEV
  ? [
      { label: "Admin", email: "admin@kidzpos.com", password: "admin123" },
      { label: "Caissier A", email: "sarah@kidzpos.com", password: "sarah123" },
      { label: "Caissier B", email: "karim@kidzpos.com", password: "karim123" },
    ]
  : [];

export default function Login() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => { preloadRoute("/"); preloadRoute("/pos"); }, []);

  if (user) return <Navigate to="/" replace />;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    const res = await login(email.trim().toLowerCase(), password);
    setLoading(false);
    if (res.ok && res.user) {
      toast.success(`Bienvenue ${res.user.name}`);
      navigate("/");
    } else {
      toast.error(res.error ?? "Identifiants invalides");
    }
  };

  return (
    <div className="grid min-h-screen lg:grid-cols-[1fr_minmax(420px,520px)]">
      {/* ── LEFT : brand panel ──────────────────────────────────────── */}
      <section className="relative hidden flex-col justify-between overflow-hidden border-r border-border bg-secondary px-12 py-12 lg:flex xl:px-16">
        {/* Subtle grid background */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 opacity-[0.4]"
          style={{
            backgroundImage:
              "linear-gradient(hsl(var(--border)) 1px, transparent 1px), linear-gradient(90deg, hsl(var(--border)) 1px, transparent 1px)",
            backgroundSize: "32px 32px",
            maskImage: "radial-gradient(ellipse 80% 60% at 50% 30%, black 30%, transparent 80%)",
          }}
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -right-32 top-1/3 h-96 w-96 rounded-full bg-primary/15 blur-3xl"
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -left-24 bottom-1/4 h-72 w-72 rounded-full bg-accent/15 blur-3xl"
        />

        {/* Brand */}
        <div className="relative flex items-center gap-3">
          <BrandMark size="lg" />
          <div className="leading-tight">
            <p className="text-base font-semibold tracking-tight">KidzPOS</p>
            <p className="text-xs text-muted-foreground">Caisse &amp; gestion</p>
          </div>
        </div>

        {/* Hero copy */}
        <div className="relative max-w-[34rem] space-y-6">
          <span className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-muted-foreground">
            <StatusDot tone="success" />
            En ligne · sync temps réel
          </span>
          <h1 className="text-[clamp(2.4rem,4.6vw,3.6rem)] font-semibold leading-[1.05] tracking-hero">
            Une caisse moderne pour vos magasins.
          </h1>
          <p className="max-w-[28rem] text-base leading-relaxed text-muted-foreground">
            Encaissement rapide, stock unifié, fidélité client et statistiques —
            en ariary, en français, hors-ligne ou en réseau.
          </p>

          <div className="flex flex-wrap gap-x-8 gap-y-3 pt-2 text-sm">
            <HeroStat number="2" label="Magasins" />
            <HeroStat number="< 1s" label="Encaissement" />
            <HeroStat number="100%" label="Hors-ligne" />
          </div>
        </div>

        {/* Demo */}
        {import.meta.env.DEV && demoAccounts.length > 0 && (
          <div className="relative w-full max-w-md space-y-2 rounded-xl border border-border bg-card p-4 shadow-card">
            <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-eyebrow text-muted-foreground">
              <ShieldCheck className="h-3.5 w-3.5 text-success" aria-hidden="true" />
              Comptes de démonstration
            </p>
            <div className="-mx-1 space-y-0.5">
              {demoAccounts.map((a) => (
                <button
                  key={a.email}
                  type="button"
                  onClick={() => { setEmail(a.email); setPassword(a.password); }}
                  aria-label={`Préremplir avec ${a.label} (${a.email})`}
                  className="group flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-xs transition hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <span className="flex items-baseline gap-2">
                    <span className="text-sm font-medium text-foreground">{a.label}</span>
                    <span className="font-mono text-label text-muted-foreground">{a.email}</span>
                  </span>
                  <ArrowRight className="h-3 w-3 text-muted-foreground/40 transition group-hover:translate-x-0.5 group-hover:text-foreground" aria-hidden="true" />
                </button>
              ))}
            </div>
          </div>
        )}
      </section>

      {/* ── RIGHT : form ──────────────────────────────────────────── */}
      <section className="flex items-center justify-center px-5 py-10 sm:px-10">
        <div className="w-full max-w-sm">
          {/* Mobile brand */}
          <div className="mb-10 flex items-center gap-2.5 lg:hidden">
            <BrandMark size="lg" />
            <div className="leading-tight">
              <p className="text-base font-semibold tracking-tight">KidzPOS</p>
              <p className="text-xs text-muted-foreground">Caisse &amp; gestion</p>
            </div>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-semibold tracking-display">Connexion</h2>
            <p className="mt-1.5 text-sm text-muted-foreground">
              Accédez à votre espace de gestion.
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <Label htmlFor="email" className="text-xs font-medium text-foreground">
                Email
              </Label>
              <Input
                id="email"
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="vous@kidzpos.com"
                className="h-10 rounded-lg border-border bg-card font-mono text-sm focus-visible:ring-2 focus-visible:ring-primary/40"
                required
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="password" className="text-xs font-medium text-foreground">
                Mot de passe
              </Label>
              <PasswordInput
                id="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="h-10 rounded-lg border-border bg-card font-mono text-sm focus-visible:ring-2 focus-visible:ring-primary/40"
                required
              />
            </div>
            <Button
              type="submit"
              variant="gradient"
              disabled={loading}
              className="group w-full rounded-lg disabled:opacity-60"
            >
              <span className="flex items-center justify-center gap-2">
                {loading ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> Connexion…
                  </>
                ) : (
                  <>
                    Se connecter
                    <ArrowRight className="h-3.5 w-3.5 transition group-hover:translate-x-0.5" aria-hidden="true" />
                  </>
                )}
              </span>
            </Button>
          </form>

          {import.meta.env.DEV && (
            <p className="mt-8 text-center font-mono text-label text-muted-foreground lg:hidden">
              Démo · admin@kidzpos.com / admin123
            </p>
          )}

          <p className="mt-10 text-center text-label text-muted-foreground/70">
            © {new Date().getFullYear()} KidzPOS · Antananarivo
          </p>
        </div>
      </section>
    </div>
  );
}

function HeroStat({ number, label }: { number: string; label: string }) {
  return (
    <div>
      <p className="text-2xl font-semibold tracking-display">{number}</p>
      <p className="mt-0.5 text-xs uppercase tracking-eyebrow text-muted-foreground">{label}</p>
    </div>
  );
}
