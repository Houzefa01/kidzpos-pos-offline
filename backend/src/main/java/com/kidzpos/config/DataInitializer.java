package com.kidzpos.config;

import com.kidzpos.domain.*;
import com.kidzpos.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final SettingsRepository settings;
    private final PasswordEncoder encoder;

    public DataInitializer(UserRepository users, StoreRepository stores,
                           ProductRepository products, SettingsRepository settings,
                           PasswordEncoder encoder) {
        this.users = users; this.stores = stores;
        this.products = products; this.settings = settings;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (stores.count() == 0) {
            stores.save(Store.builder().id("s1").name("Magasin A").location("Centre-ville").build());
            stores.save(Store.builder().id("s2").name("Magasin B").location("Banlieue").build());
        }

        if (users.count() == 0) {
            String adminPwd = resolvePassword("KIDZPOS_ADMIN_PASSWORD", "admin@kidzpos.com");
            String sarahPwd = resolvePassword("KIDZPOS_SARAH_PASSWORD", "sarah@kidzpos.com");
            String karimPwd = resolvePassword("KIDZPOS_KARIM_PASSWORD", "karim@kidzpos.com");

            users.save(User.builder().id("u1").name("Admin Principal").email("admin@kidzpos.com")
                    .passwordHash(encoder.encode(adminPwd)).role(Role.ADMIN).active(true).build());
            users.save(User.builder().id("u2").name("Sarah (Magasin A)").email("sarah@kidzpos.com")
                    .passwordHash(encoder.encode(sarahPwd)).role(Role.EMPLOYEE).storeId("s1").active(true).build());
            users.save(User.builder().id("u3").name("Karim (Magasin B)").email("karim@kidzpos.com")
                    .passwordHash(encoder.encode(karimPwd)).role(Role.EMPLOYEE).storeId("s2").active(true).build());
        }

        if (settings.count() == 0) {
            settings.save(Settings.builder().id(1L)
                    .maxDiscountPercent(10)
                    // Ariary canonical : 5000 Ar dépensés → 1 point ; 1 point = 100 Ar.
                    // Soit ~2% de retour fidélité. Ajustable via Paramètres.
                    .pointsPerAr(0.0002).arPerPoint(100)
                    .shopName("KidzPOS").currency("AR").build());
        }

        if (products.count() == 0) {
            seedProducts();
        }
    }

    /** Catalogue de démarrage en Ariary, dupliqué sur les deux magasins. */
    private void seedProducts() {
        String[] names = {
                "Ours en peluche",   "Robe été fille",   "T-shirt dinosaure", "Voiture télécommandée",
                "Puzzle 100 pièces", "Bonnet hiver",     "Cube magique",      "Poupée Lila",
                "Casque enfant",     "Sac à dos école",  "Livre coloriage",   "Trottinette pliable",
        };
        double[] prices = {
                35_000, 22_000, 18_000, 75_000,
                28_000, 12_000, 15_000, 45_000,
                35_000, 55_000,  8_000, 120_000,
        };
        String[] cats = { "Peluches", "Vêtements", "Vêtements", "Jouets",
                          "Jeux éducatifs", "Accessoires", "Jouets", "Peluches",
                          "Accessoires", "Accessoires", "Jeux éducatifs", "Jouets" };
        int[] stockBase = { 12, 8, 15, 4, 10, 20, 18, 6, 7, 9, 25, 3 };

        Instant now = Instant.now();
        for (String storeId : new String[] { "s1", "s2" }) {
            for (int i = 0; i < names.length; i++) {
                products.save(Product.builder()
                        .id("p-" + storeId + "-" + (i + 1))
                        .name(names[i])
                        .price(prices[i])
                        .stock(stockBase[i] + (storeId.equals("s2") ? 2 : 0))
                        .storeId(storeId)
                        .category(cats[i % cats.length])
                        .sku("KZ-" + storeId.toUpperCase() + "-" + (100 + i))
                        .createdAt(now.minusSeconds((long) i * 60))
                        .build());
            }
        }
        log.info("Seeded {} products across 2 stores (Ariary prices)", names.length * 2);
    }

    /**
     * Reads a password from an environment variable. If absent, generates a random UUID
     * and writes it once to STDOUT (jamais dans les logs persistés — risque fuite).
     * L'opérateur DOIT le copier au démarrage et le changer après première connexion.
     */
    private String resolvePassword(String envVar, String userEmail) {
        String pwd = System.getenv(envVar);
        if (pwd == null || pwd.isBlank()) {
            pwd = UUID.randomUUID().toString();
            // M2 : on n'envoie PAS le password dans les logs (logback peut être archivé/expédié).
            // STDOUT direct → visible au démarrage interactif, pas dans les logs structurés.
            System.out.println("============================================================");
            System.out.println("⚠ INITIAL PASSWORD GENERATED for " + userEmail);
            System.out.println("  password = " + pwd);
            System.out.println("  → Set env var " + envVar + " to suppress this message.");
            System.out.println("  → Change this password after first login.");
            System.out.println("============================================================");
            log.warn("Env var {} not set. Initial password generated for {} (printed to STDOUT only).", envVar, userEmail);
        }
        return pwd;
    }
}
