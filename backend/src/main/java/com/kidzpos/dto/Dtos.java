package com.kidzpos.dto;

import com.kidzpos.domain.PaymentMode;
import jakarta.validation.constraints.*;

import java.util.List;

public class Dtos {

    public record LoginReq(@NotBlank String email, @NotBlank String password) {}
    /** `token` = access token JWT court (15 min par défaut). Le refresh token vit
     *  dans le cookie httpOnly kidzpos_rt, jamais exposé au JS. */
    public record LoginRes(String token, UserRes user) {}
    public record RefreshRes(String accessToken) {}
    public record UserRes(String id, String name, String email, String role, String storeId, boolean active) {}

    public record CreateUserReq(
            @NotBlank String name,
            @Email String email,
            @NotBlank @Size(min = 4) String password,
            @NotBlank String role,    // ADMIN | EMPLOYEE
            String storeId,
            Boolean active
    ) {}

    public record UpdateUserReq(String name, String email, String role, String storeId, Boolean active, String password) {}

    public record StoreReq(@NotBlank String id, @NotBlank String name, String location) {}

    public record ProductReq(
            String id,
            @NotBlank String name,
            @PositiveOrZero double price,
            @PositiveOrZero int stock,
            @NotBlank String storeId,
            String category,
            @NotBlank String sku
    ) {}

    public record CustomerReq(String id, String name, String phone, String email, Integer points,
                              /** V21-bidir : storeId optionnel — permet à un admin
                               *  sur le central de cibler un magasin précis. NULL =
                               *  retombe sur NodeContext (cas local store). */
                              String storeId) {}

    public record SaleItemReq(@NotBlank String productId, @Min(1) int quantity) {}

    public record CheckoutReq(
            @NotBlank String storeId,
            // I8 : ID local optionnel pour l'idempotence offline → flush outbox.
            // Si fourni et déjà connu côté serveur, le checkout retourne la vente existante
            // au lieu d'en créer une nouvelle (évite duplication après reconnexion).
            String clientSaleId,
            @NotEmpty List<SaleItemReq> items,
            @PositiveOrZero double discount,        // en € (montant absolu après calcul UI)
            @NotNull PaymentMode paymentMode,
            Double amountPaid,
            String customerId,
            @PositiveOrZero int pointsRedeemed,
            // Devise affichée au client ("AR" | "EUR"). Optionnel : défaut 'AR' côté serveur.
            String currency
    ) {}

    /**
     * V22 — {@code clientRefundId} : UUID stable côté client pour l'idempotence
     * du refund au replay outbox. Optionnel : rétrocompat clients pré-V22.
     * Fortement recommandé pour tout nouveau client.
     */
    public record RefundReq(@NotBlank String saleId, String clientRefundId) {}

    public record StockAdjustReq(
            @NotBlank String productId,
            int delta,                  // signé
            String reason,
            // UUID stable côté client pour l'idempotence au replay outbox (cf V8).
            // Optionnel : ancien clients sans ce champ restent acceptés.
            String clientMovementId
    ) {}

    public record TransferReq(
            @NotBlank String productId,
            @NotBlank String targetStoreId,
            @Min(1) int quantity,
            String clientMovementId
    ) {}

    public record SettingsReq(
            @PositiveOrZero @DecimalMax("100") double maxDiscountPercent,
            @PositiveOrZero double pointsPerAr,
            @PositiveOrZero double arPerPoint,
            @NotBlank String shopName,
            String currency
    ) {}

    /**
     * Snapshot métriques offline-first envoyé périodiquement par chaque caisse.
     * Borne dure 100 samples / requête (sanity-check anti-flood ; valeurs hors
     * borne sont filtrées côté serveur, pas rejetées).
     *
     * P4 — Les 3 derniers champs sont des deltas cumulés depuis le précédent
     * POST réussi (reset côté client après ack). Optionnels : un client P3
     * sans ces champs reste accepté ; les Counters Prometheus ne sont juste
     * pas incrémentés pour ce report.
     */
    public record FrontendMetricsReq(
            @PositiveOrZero int outboxSize,
            @PositiveOrZero int failedReplaysCount,
            List<@PositiveOrZero @DecimalMax("3600000") Double> syncLatencyMs,
            @PositiveOrZero Long replayBatchSize,
            @PositiveOrZero Long replayThrottleDelayMs,
            @PositiveOrZero Long replayBackoffRetries,
            // P5 — État du replay adaptatif (gauges agrégés via aggregator collector).
            // Tous optionnels : un client P4 sans ces champs reste accepté.
            String replayMode,                         // "NORMAL" | "DEGRADED" | "RECOVERY"
            @PositiveOrZero Double replayAdaptiveRps,  // RPS effectif côté client
            @PositiveOrZero Integer replayAdaptiveBatchSize,
            @PositiveOrZero Long replayDegradedEntriesTotal
    ) {}
}
