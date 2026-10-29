package com.kidzpos.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Centralise le format du cookie refresh : nom, path, attributs sécurité, lifetime.
 *
 * Defaults pensés pour LAN (HTTP) :
 *   secure = false, sameSite = Lax, path = /api/auth
 *
 * En prod publique (HTTPS via reverse-proxy), exporter :
 *   KIDZPOS_AUTH_COOKIE_SECURE=true
 *   KIDZPOS_AUTH_COOKIE_SAME_SITE=Strict
 *
 * Cross-origin XHR (HTTPS uniquement) : SameSite=None + Secure=true.
 */
@Component
public class RefreshCookie {

    /** Path FIXÉ (les controllers de refresh sont sous /api/auth). */
    public static final String PATH = "/api/auth";

    /**
     * V21-fix : nom du cookie CONFIGURABLE. Permet de faire cohabiter plusieurs
     * backends sur le même hostname (test multi-serveur local) sans qu'ils
     * s'écrasent mutuellement leur cookie de refresh.
     *   - central : kidzpos.auth.cookie-name=kidzpos_rt_central
     *   - store s1 : kidzpos.auth.cookie-name=kidzpos_rt_s1
     * Défaut conservé "kidzpos_rt" pour rétrocompat prod.
     */
    private final String name;
    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSec;

    public RefreshCookie(
            @Value("${kidzpos.auth.cookie-name:kidzpos_rt}") String name,
            @Value("${kidzpos.auth.cookie-secure:false}") boolean secure,
            @Value("${kidzpos.auth.cookie-same-site:Lax}") String sameSite,
            @Value("${kidzpos.auth.refresh-token-days:30}") long days) {
        this.name = name;
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAgeSec = Duration.ofDays(days).toSeconds();
    }

    public void set(HttpServletResponse res, String cleartext) {
        ResponseCookie cookie = ResponseCookie.from(name, cleartext)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(maxAgeSec)
                .build();
        res.addHeader("Set-Cookie", cookie.toString());
    }

    public void clear(HttpServletResponse res) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(0)
                .build();
        res.addHeader("Set-Cookie", cookie.toString());
    }

    public String read(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
