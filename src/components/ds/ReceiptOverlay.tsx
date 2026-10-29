import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Receipt } from "@/components/Receipt";
import { Button } from "./Button";
import { Printer, FileText, Receipt as ReceiptIcon } from "lucide-react";
import { downloadReceiptPdf } from "@/lib/pdf";
import type { Sale, Store } from "@/store/data";
import type { Settings } from "@/store/settings";

export interface ReceiptOverlayProps {
  sale: Sale | null;
  /** Magasin associé (résolu en amont depuis sale.storeId). */
  store?: Store;
  settings: Settings;
  onClose: () => void;
  /** Personnalise le titre du Dialog. Défaut : "Reçu de vente". */
  title?: string;
}

/**
 * Dialog d'affichage d'un reçu + actions Imprimer / PDF.
 * Remplace les 2 implémentations dupliquées dans POS.tsx et Sales.tsx.
 *
 * Note : on garde l'usage de `window.print()` (la feuille @media print
 * dans index.css fait le reste). Le bouton Imprimer cible la fenêtre
 * courante — c'est le comportement attendu (le reçu visible à l'écran
 * est le seul élément visible dans la print stylesheet).
 */
export function ReceiptOverlay({ sale, store, settings, onClose, title }: ReceiptOverlayProps) {
  const seq = sale?.seq ? `#${String(sale.seq).padStart(6, "0")}` : "";
  const dialogTitle = title ?? (seq ? `Ticket ${seq}` : "Reçu de vente");

  return (
    <Dialog open={!!sale} onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-lg font-semibold tracking-display">
            <ReceiptIcon className="h-5 w-5 text-primary" aria-hidden="true" />
            {dialogTitle}
          </DialogTitle>
        </DialogHeader>
        {sale && (
          <div>
            <div className="kp-receipt-scroll">
              <Receipt sale={sale} store={store} settings={settings} />
            </div>
            <div className="mt-4 flex gap-2 no-print">
              <Button variant="outline" className="flex-1 rounded-lg" onClick={() => window.print()}>
                <Printer className="mr-2 h-4 w-4" /> Imprimer
              </Button>
              <Button
                variant="outline"
                className="flex-1 rounded-lg"
                onClick={() => downloadReceiptPdf(sale, store, settings)}
              >
                <FileText className="mr-2 h-4 w-4" /> PDF
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
