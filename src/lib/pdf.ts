import jsPDF from "jspdf";
import type { Sale, Store } from "@/store/data";
import { paymentLabel } from "@/store/data";
import type { Settings } from "@/store/settings";
import { formatMoneyAs } from "@/lib/money";
import { useExchange } from "@/store/exchange";
import { useSettings } from "@/store/settings";

export function downloadReceiptPdf(sale: Sale, store: Store | undefined, settings: Settings) {
  // Reçu individuel : on respecte la devise FIGÉE de la vente (sale.currency).
  // Pas la devise globale courante — un client doit voir son ticket dans la
  // devise dans laquelle il a payé.
  const rate = useExchange.getState().rate;
  // jsPDF base fonts (Times/Courier/Helvetica) utilisent WinAnsi/Latin-1 :
  // U+202F (fine espace insécable, séparateur de milliers FR) → "/" et
  // U+2212 (signe moins) → "?". On normalise tout en ASCII avant impression.
  const safe = (s: string) =>
    // eslint-disable-next-line no-irregular-whitespace -- U+202F (NNBSP) et U+00A0 (NBSP) intentionnels dans la regex
    s.replace(/[  ]/g, " ").replace(/[−–—]/g, "-");
  const m = (ar: number) => safe(formatMoneyAs(ar, sale.currency, rate));
  const sym = sale.currency === "AR" ? "Ar" : "€";
  const totalAmount = m(sale.total).replace(/\s+(Ar|€)$/, "").trim();
  const ticketRef = sale.seq ? String(sale.seq).padStart(6, "0") : sale.id.slice(-6);

  // Format ticket 80mm de large (~ thermal)
  const W = 80;
  const margin = 5;
  const cx = W / 2;
  const doc = new jsPDF({ unit: "mm", format: [W, 220] });
  let y = 9;

  // Palette Bureau Tropical (RGB)
  const INK: [number, number, number] = [26, 22, 18];
  const INK_SOFT: [number, number, number] = [60, 54, 46];
  const INK_MUTE: [number, number, number] = [107, 97, 87];
  const RAVINALA: [number, number, number] = [196, 105, 74];
  const setColor = (c: [number, number, number]) => doc.setTextColor(c[0], c[1], c[2]);

  const center = (text: string, opts: { font?: string; style?: string; size?: number; color?: [number, number, number]; gap?: number } = {}) => {
    doc.setFont(opts.font ?? "helvetica", opts.style ?? "normal").setFontSize(opts.size ?? 8);
    setColor(opts.color ?? INK);
    doc.text(text, cx, y, { align: "center" });
    y += (opts.size ?? 8) * 0.4 + (opts.gap ?? 0.6);
  };

  const row = (left: string, right: string, opts: { size?: number; leftColor?: [number, number, number]; rightColor?: [number, number, number]; gap?: number } = {}) => {
    const size = opts.size ?? 7.5;
    doc.setFont("helvetica", "normal").setFontSize(size);
    setColor(opts.leftColor ?? INK_SOFT);
    doc.text(left, margin, y);
    doc.setFont("courier", "normal").setFontSize(size);
    setColor(opts.rightColor ?? INK);
    doc.text(right, W - margin, y, { align: "right" });
    y += size * 0.4 + (opts.gap ?? 0.8);
  };

  const sep = (kind: "dashed" | "solid" | "double" = "dashed") => {
    doc.setDrawColor(INK[0], INK[1], INK[2]);
    if (kind === "dashed") {
      doc.setLineDashPattern([0.6, 0.6], 0);
      doc.setLineWidth(0.18);
      doc.line(margin, y, W - margin, y);
    } else if (kind === "solid") {
      doc.setLineDashPattern([], 0);
      doc.setLineWidth(0.3);
      doc.line(margin, y, W - margin, y);
    } else {
      doc.setLineDashPattern([], 0);
      doc.setLineWidth(0.15);
      doc.line(margin, y, W - margin, y);
      doc.line(margin, y + 0.6, W - margin, y + 0.6);
    }
    y += 3;
  };

  // ── Brand
  center(settings.shopName, { font: "times", style: "bold", size: 14, gap: 1.2 });
  if (store) center(store.name, { size: 7, color: INK_SOFT });
  y += 1;
  sep("dashed");

  // ── Meta
  row("Date", new Date(sale.date).toLocaleString("fr-FR"), { size: 7 });
  row("Caissier", sale.userName, { size: 7 });
  row("Ticket", `#${ticketRef}`, { size: 7 });
  if (sale.customerName) row("Client", sale.customerName, { size: 7 });

  sep("dashed");

  // ── Items
  for (const it of sale.items) {
    doc.setFont("times", "italic").setFontSize(9.5);
    setColor(INK);
    const name = it.name.length > 30 ? it.name.slice(0, 29) + "…" : it.name;
    doc.text(name, margin, y);
    doc.setFont("courier", "normal").setFontSize(8);
    doc.text(m(it.price * it.quantity), W - margin, y, { align: "right" });
    y += 3.4;
    doc.setFont("courier", "normal").setFontSize(6.5);
    setColor(INK_MUTE);
    doc.text(`${it.quantity}  ×  ${m(it.price)}`, margin + 2, y);
    y += 3.6;
  }

  sep("dashed");

  // ── Subtotals
  row("Sous-total", m(sale.subtotal));
  if (sale.discount > 0) row("Remise", `-${m(sale.discount)}`);
  if (sale.pointsRedeemed > 0) row(`Points (-${sale.pointsRedeemed})`, `-${m(sale.pointsRedeemed * settings.arPerPoint)}`);

  // ── Grand total
  y += 1;
  sep("solid");
  const totalY = y + 1;

  doc.setFont("times", "bold").setFontSize(11);
  setColor(INK);
  doc.text("Total", margin, totalY + 2);

  doc.setFont("times", "bolditalic").setFontSize(8);
  setColor(RAVINALA);
  doc.text(sym, W - margin, totalY + 2, { align: "right" });
  const symW = doc.getTextWidth(sym);

  doc.setFont("times", "bold").setFontSize(15);
  setColor(INK);
  doc.text(totalAmount, W - margin - symW - 1, totalY + 2, { align: "right" });

  y = totalY + 5;
  sep("double");

  // ── Payment
  row(paymentLabel(sale.paymentMode), m(sale.total), { leftColor: INK_SOFT });
  if (sale.paymentMode === "CASH" && sale.amountPaid != null) {
    row("Reçu", m(sale.amountPaid), { size: 7, leftColor: INK_MUTE });
  }
  if (sale.paymentMode === "CASH" && sale.change != null && sale.change > 0) {
    row("Rendu", m(sale.change), { size: 7, leftColor: INK_MUTE });
  }
  if (sale.pointsEarned > 0) {
    row("Points gagnés", `+${sale.pointsEarned}`, { size: 7, leftColor: INK_MUTE });
  }

  // ── Thanks
  y += 4;
  center("Misaotra Tompoko.", { font: "times", style: "italic", size: 12, gap: 0.8 });
  y += 0.6;
  center("Merci de votre visite.", { size: 6.5, color: INK_MUTE, gap: 1.2 });

  // ── Ticket id (sous le pied)
  y += 3;
  center(ticketRef, { font: "courier", size: 6.5, color: INK_MUTE });

  doc.save(`recu-${sale.seq ?? sale.id.slice(-6)}.pdf`);
}

export function downloadSalesReportPdf(
  sales: Sale[],
  stores: Store[],
  shopName: string,
  filterLabel: string,
) {
  // Rapport agrégé : on utilise la devise GLOBALE courante (settings.currency),
  // pas celle de chaque vente — un mélange n'aurait pas de sens dans des totaux.
  const rate = useExchange.getState().rate;
  const currency = useSettings.getState().settings.currency;
  const m = (eur: number) => formatMoneyAs(eur, currency, rate);

  const doc = new jsPDF();
  doc.setFont("helvetica", "bold").setFontSize(16);
  doc.text(`${shopName} — Rapport des ventes`, 14, 18);
  doc.setFont("helvetica", "normal").setFontSize(10);
  doc.text(filterLabel, 14, 25);
  doc.text(`Édité le ${new Date().toLocaleString("fr-FR")}`, 14, 30);

  // Stats
  const total = sales.reduce((a, s) => a + s.total, 0);
  doc.setFont("helvetica", "bold").setFontSize(12);
  doc.text(`Total : ${m(total)}`, 14, 40);
  doc.text(`Nombre de tickets : ${sales.length}`, 14, 47);

  // Table headers
  let y = 65;
  doc.setFont("helvetica", "bold").setFontSize(9);
  doc.text("Ticket", 14, y);
  doc.text("Date", 35, y);
  doc.text("Magasin", 70, y);
  doc.text("Caissier", 110, y);
  doc.text("Articles", 145, y);
  doc.text("Total", 195, y, { align: "right" });
  y += 2;
  doc.line(14, y, 196, y);
  y += 4;
  doc.setFont("helvetica", "normal").setFontSize(8);

  for (const s of sales) {
    if (y > 280) { doc.addPage(); y = 20; }
    doc.text(`#${s.seq ? String(s.seq).padStart(6, "0") : s.id.slice(-6)}`, 14, y);
    doc.text(new Date(s.date).toLocaleDateString("fr-FR"), 35, y);
    doc.text((stores.find((x) => x.id === s.storeId)?.name ?? "").slice(0, 18), 70, y);
    doc.text(s.userName.slice(0, 14), 110, y);
    doc.text(String(s.items.reduce((a, i) => a + i.quantity, 0)), 145, y);
    doc.text(m(s.total), 195, y, { align: "right" });
    y += 5;
  }

  doc.save(`rapport-ventes-${new Date().toISOString().slice(0, 10)}.pdf`);
}
