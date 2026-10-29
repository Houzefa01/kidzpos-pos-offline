package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Settings {
    @Id
    private Long id;          // toujours 1
    @Column(nullable = false) private double maxDiscountPercent;
    /** Devise canonique = Ariary. Points gagnés = (montant en Ar) × pointsPerAr.
     *  Conversion en EUR uniquement à l'affichage côté frontend. */
    @Column(name = "points_per_ar", nullable = false) private double pointsPerAr;
    /** Valeur en Ariary d'un point fidélité. */
    @Column(name = "ar_per_point", nullable = false) private double arPerPoint;
    @Column(nullable = false) private String shopName;
    /** Devise par défaut affichée : "AR" (Ariary) ou "EUR" (Euro). Stockage = AR. */
    @Column(nullable = false) private String currency;
}
