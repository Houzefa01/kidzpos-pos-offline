package com.kidzpos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Exclusion de UserDetailsServiceAutoConfiguration : notre auth est entièrement
 * portée par JwtAuthFilter + JwtService (cf SecurityConfig). L'auto-config Spring
 * Boot, sans bean UserDetailsService, génère un user "user" + password aléatoire
 * imprimé au boot (bruit inutile + faux signal de configuration). On la désactive.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
public class KidzposApplication {
    public static void main(String[] args) {
        SpringApplication.run(KidzposApplication.class, args);
    }
}
