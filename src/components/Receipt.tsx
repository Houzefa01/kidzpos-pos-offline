import { useId } from "react";
import type { Sale, Store } from "@/store/data";
import { paymentLabel } from "@/store/data";
import type { Settings } from "@/store/settings";
import { useFormatMoney, currencySymbol } from "@/lib/money";

interface ReceiptProps {
  sale: Sale;
  store?: Store;
  settings: Settings;
}

function BarcodeBars() {
  // SVG (contenu de premier plan) plutôt que background-image : les
  // navigateurs ne rendent pas les arrière-plans à l'impression par défaut.
  // useId garantit l'unicité du pattern id si plusieurs reçus coexistent.
  const id = `kp-bars-${useId().replace(/[^\w]/g, "")}`;
  return (
    <svg
      className="kp-receipt-barcode-bars"
      viewBox="0 0 110 100"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <defs>
        <pattern id={id} width="11" height="100" patternUnits="userSpaceOnUse">
          <g fill="currentColor">
            <rect x="0" y="0" width="1" height="100" />
            <rect x="3" y="0" width="2" height="100" />
            <rect x="6" y="0" width="1" height="100" />
          </g>
        </pattern>
      </defs>
      <rect width="110" height="100" fill={`url(#${id})`} />
    </svg>
  );
}

export function Receipt({ sale, store, settings }: ReceiptProps) {
  const fmt = useFormatMoney();
  const m = (v: number) => fmt(v, sale.currency);
  const sym = currencySymbol(sale.currency);
  const totalAmount = fmt(sale.total, sale.currency, { withSymbol: false });

  const ticketRef = sale.seq ? String(sale.seq).padStart(6, "0") : sale.id.slice(-6);
  const dateStr = new Date(sale.date).toLocaleString("fr-FR");

  return (
    <article className="kp-receipt-root print-receipt">
      <header className="kp-receipt-head">
        <div className="kp-receipt-brand">{settings.shopName}</div>
        {store && <div className="kp-receipt-addr">{store.name}</div>}
      </header>

      <hr className="kp-receipt-divider" />

      <dl className="kp-receipt-meta">
        <dt>Date</dt>     <dd>{dateStr}</dd>
        <dt>Caissier</dt> <dd>{sale.userName}</dd>
        <dt>Ticket</dt>   <dd>#{ticketRef}</dd>
        {sale.customerName && (<><dt>Client</dt><dd>{sale.customerName}</dd></>)}
      </dl>

      <hr className="kp-receipt-divider" />

      <div className="kp-receipt-items">
        {sale.items.map((it) => (
          <div key={it.productId} className="kp-receipt-line">
            <div className="kp-receipt-line-row">
              <span className="kp-receipt-line-name">{it.name}</span>
              <span className="kp-receipt-line-amt">{m(it.price * it.quantity)}</span>
            </div>
            <div className="kp-receipt-line-detail">
              {it.quantity}&nbsp;×&nbsp;{m(it.price)}
            </div>
          </div>
        ))}
      </div>

      <hr className="kp-receipt-divider" />

      <div className="kp-receipt-tot"><span>Sous-total</span><span>{m(sale.subtotal)}</span></div>
      {sale.discount > 0 && (
        <div className="kp-receipt-tot"><span>Remise</span><span>−{m(sale.discount)}</span></div>
      )}
      {sale.pointsRedeemed > 0 && (
        <div className="kp-receipt-tot">
          <span>Points utilisés ({sale.pointsRedeemed})</span>
          <span>−{m(sale.pointsRedeemed * settings.arPerPoint)}</span>
        </div>
      )}
      <div className="kp-receipt-grand">
        <span className="kp-receipt-grand-label">Total</span>
        <span>
          <span className="kp-receipt-grand-amt">{totalAmount}</span>
          <span className="kp-receipt-grand-cur">{sym}</span>
        </span>
      </div>

      <div className="kp-receipt-tot kp-receipt-pay">
        <span>{paymentLabel(sale.paymentMode)}</span>
        <span>{m(sale.total)}</span>
      </div>
      {sale.paymentMode === "CASH" && sale.amountPaid != null && (
        <div className="kp-receipt-tot"><span>Reçu</span><span>{m(sale.amountPaid)}</span></div>
      )}
      {sale.paymentMode === "CASH" && sale.change != null && sale.change > 0 && (
        <div className="kp-receipt-tot"><span>Rendu</span><span>{m(sale.change)}</span></div>
      )}
      {sale.pointsEarned > 0 && (
        <div className="kp-receipt-tot"><span>Points gagnés</span><span>+{sale.pointsEarned}</span></div>
      )}

      <div className="kp-receipt-thanks">
        <div className="kp-receipt-thanks-display">Misaotra<br />Tompoko.</div>
        <div className="kp-receipt-thanks-sub">Merci de votre visite.</div>
      </div>

      <div className="kp-receipt-barcode">
        <BarcodeBars />
        {ticketRef}
      </div>
    </article>
  );
}
