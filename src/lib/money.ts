// Helper centralisé pour afficher les montants dans la devise active.
// Source de vérité : useSettings().settings.currency ("AR" | "EUR")
//
// Devise canonique = Ariary. Tous les montants stockés (backend + state) sont en AR.
// EUR n'existe que comme vue d'affichage : on divise par le taux EUR→AR pour convertir.

import { useSettings } from "@/store/settings";
import { useExchange } from "@/store/exchange";

const fmtAr = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 });
const fmtEur = new Intl.NumberFormat("fr-FR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Helper pur — formate un montant Ariary dans la devise demandée avec le taux fourni.
 * Utilisable hors composant React (PDF, exports, tests).
 *
 * `rate` = nombre d'Ariary par 1 EUR (ex: 4900). Pour AR→EUR : on divise.
 * `opts.withSymbol = false` retire " Ar" / " €" du résultat (utile pour les gros
 * affichages où le symbole est rendu séparément, type cadran de total).
 */
export function formatMoneyAs(
  amountAr: number,
  currency: "AR" | "EUR",
  rate: number,
  opts?: { withSymbol?: boolean },
): string {
  const withSymbol = opts?.withSymbol !== false;
  if (currency === "EUR") {
    const eur = rate <= 0 ? 0 : amountAr / rate;
    return withSymbol ? `${fmtEur.format(eur)} €` : fmtEur.format(eur);
  }
  const ar = fmtAr.format(Math.round(amountAr));
  return withSymbol ? `${ar} Ar` : ar;
}

/**
 * Saisie utilisateur (dans la devise affichée) → montant Ariary à stocker.
 * Inverse de formatMoneyAs. Si l'utilisateur tape en EUR, on multiplie par le taux.
 */
export function parseMoneyToAr(amount: number, currency: "AR" | "EUR", rate: number): number {
  if (!Number.isFinite(amount)) return 0;
  if (currency === "EUR") return amount * rate;
  return amount;
}

/** Stockage = Ariary. Retourne la chaîne formatée dans la devise courante. */
export function formatMoney(amountAr: number): string {
  const { currency } = useSettings.getState().settings;
  const rate = useExchange.getState().rate;
  return formatMoneyAs(amountAr, currency, rate);
}

/** Symbole court ("Ar" / "€"). Accepte un override (ex: devise figée d'une vente). */
export function currencySymbol(override?: "AR" | "EUR"): string {
  const cur = override ?? useSettings.getState().settings.currency;
  return cur === "AR" ? "Ar" : "€";
}

/**
 * Hook : se ré-évalue automatiquement quand la devise ou le taux changent.
 */
export function useFormatMoney() {
  const currency = useSettings((s) => s.settings.currency);
  const rate = useExchange((s) => s.rate);
  // `override` permet d'afficher un montant dans la devise figée d'une vente passée
  // (cf. Sale.currency), indépendamment du réglage global courant.
  return (amountAr: number, override?: "AR" | "EUR", opts?: { withSymbol?: boolean }) =>
    formatMoneyAs(amountAr, override ?? currency, rate, opts);
}
