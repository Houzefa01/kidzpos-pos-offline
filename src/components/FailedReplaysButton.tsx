import { useState } from "react";
import { AlertCircle } from "lucide-react";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { useFailedReplays } from "@/store/failedReplays";

/**
 * Badge cliquable affichant le nombre de mutations rejetées (HTTP 4xx) au
 * moment du replay outbox. Ouvre un dialogue listant chaque échec avec son
 * code statut, message backend, horodatage, et un bouton "Acquitter" par ligne.
 *
 * Le store useFailedReplays est persisté en localStorage (cap 100). L'opérateur
 * doit pouvoir consulter et acquitter même après reload — d'où la persistance.
 *
 * Aucun changement UX silencieux : la mutation a déjà déclenché un toast
 * d'erreur au moment du replay (cf syncService.drainQueue), ce dialogue
 * permet de revoir l'historique a posteriori.
 */
export function FailedReplaysButton({ count }: { count: number }) {
  const [open, setOpen] = useState(false);
  const failures = useFailedReplays((s) => s.failures);
  const remove = useFailedReplays((s) => s.remove);
  const clear = useFailedReplays((s) => s.clear);

  if (count === 0) return null;

  const plural = count > 1 ? "s" : "";

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <button
          type="button"
          aria-label={`${count} mutation${plural} rejetée${plural} — voir les détails`}
          className="inline-flex items-center gap-1 rounded-full bg-destructive/20 px-2 py-0.5 text-[11px] font-semibold text-destructive hover:bg-destructive/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-destructive focus-visible:ring-offset-1"
        >
          <AlertCircle aria-hidden className="h-3 w-3" />
          {count} échec{plural}
        </button>
      </DialogTrigger>

      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Mutations rejetées par le serveur</DialogTitle>
          <DialogDescription>
            Ces actions ont été refusées (HTTP 4xx) au moment de la
            synchronisation et n'ont pas été appliquées en base. Vérifiez la
            cause, puis acquittez ou recommencez l'action.
          </DialogDescription>
        </DialogHeader>

        {failures.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            Aucun échec en attente.
          </p>
        ) : (
          <>
            <ScrollArea className="max-h-[60vh] pr-3">
              <ul className="space-y-2">
                {failures.map((f) => {
                  const ts = new Date(f.ts);
                  return (
                    <li
                      key={f.id}
                      className="rounded-md border border-destructive/30 bg-destructive/5 p-3"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-baseline gap-2 text-xs">
                            <span className="font-mono font-semibold">{f.method}</span>
                            <span
                              className="truncate font-mono text-muted-foreground"
                              title={f.path}
                            >
                              {f.path}
                            </span>
                            <span className="rounded bg-destructive/20 px-1.5 py-0.5 font-mono font-semibold text-destructive">
                              {f.status}
                            </span>
                          </div>
                          <p className="mt-1 text-sm">{f.error}</p>
                          <time
                            dateTime={ts.toISOString()}
                            className="mt-1 block text-[11px] text-muted-foreground"
                          >
                            {ts.toLocaleString("fr-FR")}
                          </time>
                        </div>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => remove(f.id)}
                          aria-label={`Acquitter l'échec ${f.method} ${f.path}`}
                        >
                          Acquitter
                        </Button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </ScrollArea>

            <div className="flex justify-end">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  clear();
                  setOpen(false);
                }}
              >
                Tout acquitter
              </Button>
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}
