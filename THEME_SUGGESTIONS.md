# Suggestions de thème — KidzPOS

Document de travail. Pour chaque idée : la **direction**, des **tokens HSL prêts à coller** dans `src/index.css`, et la **raison** pour laquelle ça pourrait convenir à un POS Madagascar.

Le thème actuel est **Atelier** (indigo + rose sur neutres frais, typo Geist). Tout ce qui suit est compatible — on peut en piocher une, deux, ou les mixer.

---

## A. Palettes alternatives

Chaque palette donne les **7 tokens essentiels** en HSL (sans la fonction `hsl()`, format Tailwind) pour le mode clair. Le mode sombre est dérivé en inversant la luminosité (bg ↔ fg) et en remontant légèrement la saturation du primary.

### A.1 — Atelier (actuel)
**Mood :** fintech moderne, confiance, accent passionné.
Indigo confiant + rose vif. Standard 2024-2026.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `225 22% 98%` | `225 24% 7%` |
| `--foreground` | `225 25% 11%` | `225 18% 96%` |
| `--primary` | `232 72% 54%` | `232 78% 64%` |
| `--accent` | `335 72% 56%` | `335 78% 64%` |
| `--success` | `156 60% 38%` | `156 62% 50%` |
| `--warning` | `38 92% 50%` | `38 92% 58%` |
| `--destructive` | `350 75% 50%` | `350 75% 60%` |

---

### A.2 — Vanille & Vetiver
**Mood :** Madagascar éditorial, papier chaud, accent végétal.
Crème ivoire + vetiver profond (le vert des champs d'épices). Plus chaud que l'Atelier, plus enraciné dans le terroir. Naturellement parent visuel du reçu Bureau Tropical.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `42 35% 96%` | `155 18% 8%` |
| `--foreground` | `155 30% 12%` | `42 30% 94%` |
| `--primary` | `155 65% 28%` | `155 60% 50%` |
| `--accent` | `28 75% 52%` | `28 85% 62%` |
| `--success` | `155 65% 28%` | `155 60% 50%` |
| `--warning` | `38 92% 50%` | `38 92% 58%` |
| `--destructive` | `0 75% 48%` | `0 78% 60%` |

Pourquoi ça marche : la combinaison vert-profond + orange-cannelle est *intemporelle* (Aesop, Le Labo). Sur un POS, le vert signifie "argent qui rentre" sans cliché.

---

### A.3 — Encre & Cuivre
**Mood :** Bleu nuit luxueux + cuivre patiné.
Sophistiqué, masculin sans être austère. Lit comme un carnet en cuir.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `30 18% 96%` | `220 40% 8%` |
| `--foreground` | `220 45% 14%` | `30 20% 94%` |
| `--primary` | `220 65% 32%` | `220 75% 62%` |
| `--accent` | `22 65% 48%` | `22 78% 60%` |
| `--success` | `158 55% 35%` | `158 55% 50%` |
| `--warning` | `38 88% 52%` | `38 92% 58%` |
| `--destructive` | `0 70% 45%` | `0 78% 60%` |

Pourquoi ça marche : très différent de la "tech rose-bleue" omniprésente. Donne une crédibilité de comptable / notaire. Le cuivre fait l'écho du ravinala du reçu.

---

### A.4 — Charbon & Néon
**Mood :** Brutaliste high-contrast. Statement.
Fond presque noir en mode sombre par défaut, accent rose néon. Très distinctif. Plus risqué — peut sembler "boutique de sneakers" si mal calibré.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `0 0% 97%` | `0 0% 6%` |
| `--foreground` | `0 0% 8%` | `0 0% 96%` |
| `--primary` | `330 95% 56%` | `330 95% 64%` |
| `--accent` | `190 90% 48%` | `190 90% 58%` |
| `--success` | `145 70% 40%` | `145 70% 55%` |
| `--warning` | `48 100% 55%` | `48 100% 62%` |
| `--destructive` | `0 90% 55%` | `0 90% 65%` |

Pourquoi ça marche / quand éviter : look mémorable, mais peut fatiguer après 8h derrière la caisse. À tester avec les caissiers.

---

### A.5 — Sable & Lagon
**Mood :** Côtier doux. Sable chaud + bleu lagon turquoise.
Évoque Mahajanga / Nosy Be. Hospitalier, sans être enfantin.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `34 40% 96%` | `200 35% 9%` |
| `--foreground` | `200 35% 14%` | `34 35% 92%` |
| `--primary` | `192 78% 38%` | `192 75% 55%` |
| `--accent` | `12 85% 56%` | `12 88% 64%` |
| `--success` | `162 55% 38%` | `162 60% 52%` |
| `--warning` | `38 92% 52%` | `38 92% 58%` |
| `--destructive` | `0 75% 50%` | `0 78% 60%` |

Pourquoi ça marche : le turquoise lit toujours "vacances / chaleur" — bien pour une chaîne ciblant la famille. Le rouge-corail accent reste assez sobre pour ne pas faire kitsch.

---

### A.6 — Graphite & Rubis
**Mood :** Magazine élégant. Gris graphite + rouge profond ponctuel.
Lit comme un papier d'affaires haut de gamme. Pour un positionnement premium.

| Token | Clair | Sombre |
|---|---|---|
| `--background` | `30 8% 97%` | `30 6% 10%` |
| `--foreground` | `30 10% 14%` | `30 8% 94%` |
| `--primary` | `30 10% 22%` | `30 12% 80%` |
| `--accent` | `352 78% 42%` | `352 78% 58%` |
| `--success` | `145 50% 32%` | `145 50% 50%` |
| `--warning` | `38 88% 50%` | `38 92% 58%` |
| `--destructive` | `0 78% 48%` | `0 80% 60%` |

Pourquoi ça marche : le **primary est neutre** (gris graphite). Tout l'accent visuel est concentré sur les CTA (rouge rubis). Très peu de couleur = très professionnel. Risque : trop austère pour une boutique d'articles enfants — à pondérer.

---

## B. Couples typographiques alternatifs

Le couple actuel **Geist + Geist Mono** est excellent. Voici 3 alternatives crédibles :

### B.1 — Inter Tight + JetBrains Mono
La plus *safe*. Inter Tight a un tracking serré qui le rapproche de Geist mais avec plus de chaleur dans les courbes. Très lisible aux petites tailles. JetBrains Mono déjà chargé pour le reçu — on économise un fichier de fontes.

URL Google Fonts : `family=Inter+Tight:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600`

### B.2 — General Sans + Berkeley Mono
**General Sans** (Indian Type Foundry) a une personnalité distinctive sans être bizarre — un peu plus "designed" que Geist. **Berkeley Mono** (commercial, payant) est l'option premium absolue. Si budget zéro : remplacer par **DM Mono**.

Inconvénient : General Sans nécessite hébergement (Fontshare) ou self-host.

### B.3 — Söhne (premium) + IBM Plex Mono
**Söhne** est la typo *de référence* du software haut de gamme (Vercel, Mercury, Klarna). Elle coûte cher (~$200 pour le pack web). **IBM Plex Mono** est gratuit et incroyable.

Si Söhne hors budget, le remplaçant gratuit le plus proche est **Outfit** ou **Onest** sur Google Fonts.

### B.4 — Pour ajouter un côté serif éditorial
**Fraunces** (déjà chargée pour le reçu) peut être *réintroduite* sélectivement :
- Sur les **titres de page** uniquement (Login hero, Sales H1, Dashboard H1)
- Garder Geist en sans pour tout le reste

Code :
```css
h1.hero {
  font-family: 'Fraunces', serif;
  font-variation-settings: 'opsz' 96, 'SOFT' 60;
  font-weight: 500;
  letter-spacing: -0.04em;
}
```

Gagne immédiatement en personnalité éditoriale sur les pages-clés, tout en gardant l'app opérationnelle en sans neutre. **Recommandation forte si la palette part vers A.2 (Vanille) ou A.6 (Graphite).**

---

## C. Idées de polish & micro-interactions

### C.1 — Skeleton loaders avec shimmer
Aujourd'hui : pas de placeholder pendant le chargement (`Suspense` montre du blanc).
Ajouter un `<Skeleton>` avec un dégradé en mouvement de gauche à droite.

```css
@keyframes shimmer {
  100% { transform: translateX(100%); }
}
.skeleton {
  position: relative;
  overflow: hidden;
  background: hsl(var(--muted));
  border-radius: var(--radius);
}
.skeleton::after {
  content: "";
  position: absolute; inset: 0;
  background: linear-gradient(90deg, transparent, hsl(var(--card) / 0.6), transparent);
  animation: shimmer 1.6s infinite;
  transform: translateX(-100%);
}
```

### C.2 — Count-up animé sur les totaux
Quand la valeur du total change (ajout d'article), au lieu d'un saut brusque, un compteur qui s'incrémente sur ~400ms. Bibliothèque : `react-countup` (1.5 kB) ou implémentation maison avec `requestAnimationFrame`.

### C.3 — Confetti subtil à la fin d'une vente
À l'ouverture du dialog reçu : 3-4 confettis discrets en haut, fade-out en 800ms. Renforce le "moment positif" sans être enfantin. Bibliothèque : `canvas-confetti` (~5 kB) avec params très conservateurs.

### C.4 — Pulse sur le statut de connexion
Le point vert "En ligne" dans le header (déjà présent) pourrait pulser doucement (animation 2s ease-in-out). Signal de vie discret.

### C.5 — Hover parallax sur les product cards
Au survol, l'emoji de catégorie translate de 2-3px vers le coin opposé du curseur. Très subtil, donne du "poids" aux cartes. Implémentation avec `onMouseMove` + CSS transform.

### C.6 — Sound feedback (opt-in)
Tick discret au scan SKU, *cha-ching* discret à l'encaissement. Toggle dans Settings (off par défaut). Améliore l'UX caissier en environnement bruyant.

### C.7 — Mode focus / concentration
Touche dédiée (ex : F11 ou raccourci) qui masque sidebar + header → plein écran POS. Pour les rushs.

### C.8 — Badge "low stock" avec gradient animé
Quand un produit passe à `stock <= 3` : badge avec un fin gradient orange→rouge animé en boucle lente (3s). Attire l'œil sans crier.

---

## D. Accessibilité & finitions

### D.1 — Vérifier contraste WCAG AAA
Le `text-muted-foreground` actuel (`225 12% 42%`) sur `bg` (`225 22% 98%`) est probablement AA mais pas AAA. À tester avec un outil comme [WebAIM Contrast Checker]. Si non-AAA, foncer le muted à `225 12% 36%`.

### D.2 — Focus-visible globaux
Aujourd'hui les inputs ont un focus ring discret (`focus-visible:ring-2 focus-visible:ring-primary/40`). Les liens, boutons custom, et navlinks devraient avoir le même traitement. Audit rapide → corriger.

### D.3 — Respecter `prefers-reduced-motion`
Ajouter dans `index.css` :
```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

### D.4 — Labels aria sur boutons icon-only
Plusieurs boutons utilisent uniquement `title=` (tooltip). Ajouter `aria-label` pour les lecteurs d'écran. Audit : `Receipt`, `Trash2`, `Plus/Minus` du panier, actions du tableau Sales.

### D.5 — Mode imprimable des autres pages
Le reçu a son `@media print`. Sales (PDF du jour) et Dashboard pourraient aussi avoir un mode print propre pour l'impression rapide depuis le navigateur (sans passer par jsPDF).

---

## E. Variantes de marque

### E.1 — Garder le monogramme "K" actuel
Carré arrondi + K géométrique, dégradé indigo→rose. **C'est l'état actuel.** Mémorable, scalable, fonctionne en favicon 16×16.

### E.2 — Wordmark seul
Supprimer le carré, garder uniquement "**KidzPOS**" en Geist 600 avec un soulignement gradient subtil sous le "K". Plus minimal, plus "tech".

### E.3 — Marque géométrique abstraite
3 cercles concentriques d'épaisseur croissante (cible / spiral) en gradient. Évoque la précision + l'inventaire qui se construit. Plus abstrait, moins explicite.

### E.4 — Pétale stylisé
Reprendre l'idée bougainvillée mais sous forme **purement géométrique** : 3 pétales asymétriques en triangles arrondis, sans représentation florale. Lien subtil avec Madagascar sans cliché.

---

## F. Prochaines étapes recommandées (par ordre d'impact)

Si tu dois choisir un ordre :

1. **Mettre les `@media (prefers-reduced-motion)` et les `aria-label`** — accessibilité, 30 min de travail, gain réel.
2. **Tester une 2e palette en dur** pour 24h (idéalement A.2 Vanille ou A.6 Graphite) — voir si on garde Atelier ou si on bascule.
3. **Réintroduire Fraunces sur les titres-hero uniquement** — gros gain de personnalité pour un risque très faible.
4. **Ajouter les skeleton loaders** — meilleure perception de vitesse.
5. **Count-up sur le total POS** — moment "wow" discret quand on ajoute un article.

---

*Dernière mise à jour : 2026-05-12. Document destiné à évoluer — supprimer les options écartées plutôt que de les archiver, pour garder le doc actionnable.*
