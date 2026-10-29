package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.MovementType;
import com.kidzpos.domain.Sale;
import com.kidzpos.domain.SaleItem;
import com.kidzpos.domain.StockMovement;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.SaleRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "sale.checkout" — matérialise une vente dans la table sales locale.
 *
 * Cas d'usage principal : sur le CENTRAL, quand un store pousse une vente,
 * le payload contient la Sale sérialisée. On la déserialise et on l'insère
 * dans central.sales (et ses items via cascade).
 *
 * Sur un STORE (pull retournant ses propres events), l'event est skip silencieux
 * via {@code existsById} → idempotent.
 *
 * Cross-store : le processor a déjà filtré (V18 cross-store guard) — sur un
 * nœud store-scoped, seuls les events du store local arrivent ici. Sur le
 * central, tous les events arrivent — c'est l'intention de l'agrégation.
 */
@Component
public class SaleCheckoutHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleCheckoutHandler.class);

    private final SaleRepository sales;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final ObjectMapper mapper;

    public SaleCheckoutHandler(SaleRepository sales,
                               ProductRepository products,
                               StockMovementRepository movements,
                               ObjectMapper mapper) {
        this.sales = sales;
        this.products = products;
        this.movements = movements;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "sale.checkout";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String saleId = textNonNull(payload, "id");
        if (saleId == null) {
            throw new IllegalArgumentException("sale.checkout: missing 'id' in payload");
        }

        if (sales.existsById(saleId)) {
            log.debug("[sale.checkout] {} already exists — skip", saleId);
            return;
        }

        Sale s;
        try {
            s = mapper.treeToValue(payload, Sale.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("sale.checkout: cannot deserialize: " + e.getMessage());
        }

        // V21-bidir : RÉALLOUER un seq local pour éviter le conflit uk_sale_store_seq.
        // Le seq est un numéro de ticket PER-NODE — chaque nœud a sa propre numérotation
        // pour le même magasin. L'IDENTIFIANT global de la vente reste son UUID (s.id).
        // Sans ça, deux nœuds qui créent des ventes en parallèle pour le même store
        // tomberaient sur le même seq et l'INSERT échouerait.
        long localSeq = sales.findMaxSeqByStoreId(s.getStoreId()).orElse(0L) + 1;
        s.setSeq(localSeq);

        // V21 : reset l'id auto-généré des items pour ne pas tenter d'INSERT avec
        // un id provenant du noeud source (le sale_items.id est BIGSERIAL IDENTITY,
        // chaque nœud a sa propre séquence). Jackson a posé item.id depuis le JSON,
        // on le remet à null pour que Postgres génère un nouvel id local.
        if (s.getItems() != null) {
            for (SaleItem item : s.getItems()) {
                if (item.getSale() == null) item.setSale(s);
                item.setId(null);   // ← force nouvelle séquence locale
            }
        }
        sales.save(s);

        // V21-stock : matérialiser AUSSI l'effet stock — décrément produit
        // + journal stock_movements. Sans ça, le sale apparaît sur l'autre
        // nœud mais le stock reste figé → ce que voit le user.
        // Idempotent globalement via existsById(saleId) au début du handler :
        // si on re-passe pour la même vente, on ne re-décrémente pas.
        if (s.getItems() != null) {
            Instant saleDate = s.getDate() != null ? s.getDate() : Instant.now();
            for (SaleItem item : s.getItems()) {
                products.findByIdIncludingDeleted(item.getProductId()).ifPresent(p -> {
                    p.setStock(Math.max(0, p.getStock() - item.getQuantity()));
                    products.save(p);
                    movements.save(StockMovement.builder()
                            .productId(p.getId())
                            .storeId(p.getStoreId())
                            .type(MovementType.SALE)
                            .quantity(-item.getQuantity())
                            .date(saleDate)
                            .userId(s.getUserId())
                            .relatedSaleId(s.getId())
                            .build());
                });
            }
        }

        log.debug("[sale.checkout] materialized id={} storeId={} items={}",
                saleId, s.getStoreId(), s.getItems() == null ? 0 : s.getItems().size());
    }

    private static String textNonNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
