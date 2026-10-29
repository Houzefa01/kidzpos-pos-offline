package com.kidzpos;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base pour les tests d'intégration : démarre un Postgres réel via TestContainers,
 * branche le DataSource Spring dessus via {@link DynamicPropertySource}.
 *
 * Le container est singleton (static) → partagé entre toutes les classes de test
 * de la JVM. Chaque test TRUNCATE les tables qu'il touche.
 *
 * Why {@code @DynamicPropertySource} plutôt que {@code @ServiceConnection} :
 * la nested {@code @TestConfiguration} d'une superclasse abstraite n'est pas
 * auto-scannée par Spring Boot (elle ne s'applique qu'aux beans nested dans la
 * classe de test concrète), ce qui faisait fallback sur le datasource par défaut
 * (localhost:5432). {@code @DynamicPropertySource} est hérité correctement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("kidzpos_test")
                .withUsername("kidzpos")
                .withPassword("kidzpos")
                .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
