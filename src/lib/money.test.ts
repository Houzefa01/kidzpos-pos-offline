import { describe, it, expect } from "vitest";
import { formatMoneyAs, parseMoneyToAr, currencySymbol } from "./money";

// fr-FR utilise des espaces insécables fines (U+202F) comme séparateur de
// milliers. On normalise pour des assertions stables sur le contenu, peu
// importe le caractère d'espacement exact choisi par Intl.
const stripWs = (s: string) => s.replace(/\s/g, "");

describe("parseMoneyToAr — saisie utilisateur (devise affichée) → Ariary stocké", () => {
  it("AR : identité, aucune conversion", () => {
    expect(parseMoneyToAr(4900, "AR", 4900)).toBe(4900);
  });

  it("EUR : multiplie par le taux EUR→AR", () => {
    expect(parseMoneyToAr(1, "EUR", 4900)).toBe(4900);
    expect(parseMoneyToAr(2.5, "EUR", 4900)).toBe(12250);
  });

  it("NaN / Infinity → 0 (garde-fou saisie invalide au clavier POS)", () => {
    expect(parseMoneyToAr(NaN, "EUR", 4900)).toBe(0);
    expect(parseMoneyToAr(Infinity, "AR", 4900)).toBe(0);
    expect(parseMoneyToAr(-Infinity, "EUR", 4900)).toBe(0);
  });

  it("0 et négatif passent tels quels (remise, avoir/refund)", () => {
    expect(parseMoneyToAr(0, "EUR", 4900)).toBe(0);
    expect(parseMoneyToAr(-10, "AR", 4900)).toBe(-10);
  });
});

describe("formatMoneyAs — montant Ariary stocké → chaîne affichée", () => {
  it("AR : arrondit à l'entier, suffixe ' Ar', zéro décimale", () => {
    const out = formatMoneyAs(4900.7, "AR", 4900);
    expect(out.endsWith("Ar")).toBe(true);
    expect(stripWs(out)).toBe("4901Ar");
  });

  it("EUR : divise par le taux, 2 décimales, suffixe ' €'", () => {
    const out = formatMoneyAs(4900, "EUR", 4900);
    expect(out.endsWith("€")).toBe(true);
    expect(stripWs(out)).toBe("1,00€");
  });

  it("taux <= 0 → EUR vaut 0 (anti division par zéro)", () => {
    expect(stripWs(formatMoneyAs(4900, "EUR", 0))).toBe("0,00€");
    expect(stripWs(formatMoneyAs(4900, "EUR", -1))).toBe("0,00€");
  });

  it("withSymbol:false retire le suffixe devise (cadran de total)", () => {
    expect(formatMoneyAs(4900, "AR", 4900, { withSymbol: false }).includes("Ar")).toBe(false);
    expect(formatMoneyAs(4900, "EUR", 4900, { withSymbol: false }).includes("€")).toBe(false);
  });
});

describe("round-trip parseMoneyToAr ∘ formatMoneyAs (invariant POS)", () => {
  it("1 € saisi → 4900 Ar stocké → réaffiché 1,00 €", () => {
    const ar = parseMoneyToAr(1, "EUR", 4900);
    expect(ar).toBe(4900);
    expect(stripWs(formatMoneyAs(ar, "EUR", 4900))).toBe("1,00€");
  });
});

describe("currencySymbol", () => {
  it("override explicite : AR → 'Ar', EUR → '€'", () => {
    expect(currencySymbol("AR")).toBe("Ar");
    expect(currencySymbol("EUR")).toBe("€");
  });
});
