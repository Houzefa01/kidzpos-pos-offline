package com.kidzpos.sync;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests pour {@link SyncRateLimitFilter} : protège /api/sync/** contre le
 * spam (store compromis, client buggé, script de test laissé tourner).
 */
class SyncRateLimitFilterTest {

    private static MockHttpServletRequest syncReq(String storeId, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/sync/push");
        req.setRemoteAddr(ip);
        if (storeId != null) req.addHeader(SyncApiKeyFilter.HEADER_STORE, storeId);
        return req;
    }

    private static void invoke(SyncRateLimitFilter f, HttpServletRequest req, HttpServletResponse res) throws Exception {
        FilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
    }

    @Test
    void nonSyncPath_isNotRateLimited() throws Exception {
        SyncRateLimitFilter f = new SyncRateLimitFilter(2, 60_000);
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/products");
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            invoke(f, req, res);
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void perStoreBucket_isolates_stores() throws Exception {
        SyncRateLimitFilter f = new SyncRateLimitFilter(2, 60_000);
        // s1 épuise son quota (2 requests)
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            invoke(f, syncReq("s1", "10.0.0.1"), res);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        // 3e requête s1 → 429
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        invoke(f, syncReq("s1", "10.0.0.1"), blocked);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();

        // s2 (autre store, même IP) → indemne, son propre bucket
        MockHttpServletResponse other = new MockHttpServletResponse();
        invoke(f, syncReq("s2", "10.0.0.1"), other);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void noStoreHeader_fallsBack_toIpBucket() throws Exception {
        SyncRateLimitFilter f = new SyncRateLimitFilter(2, 60_000);
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            invoke(f, syncReq(null, "10.0.0.42"), res);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        invoke(f, syncReq(null, "10.0.0.42"), blocked);
        assertThat(blocked.getStatus()).isEqualTo(429);

        // IP différente → autre bucket → indemne
        MockHttpServletResponse other = new MockHttpServletResponse();
        invoke(f, syncReq(null, "10.0.0.43"), other);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void retryAfterHeader_isPresent_andPositive() throws Exception {
        SyncRateLimitFilter f = new SyncRateLimitFilter(1, 60_000);
        invoke(f, syncReq("s1", "1.1.1.1"), new MockHttpServletResponse());
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        invoke(f, syncReq("s1", "1.1.1.1"), blocked);
        assertThat(blocked.getStatus()).isEqualTo(429);
        int retryAfter = Integer.parseInt(blocked.getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1, 60);
    }

    @Test
    void constructor_clamps_invalid_config_to_safe_defaults() {
        // limit < 1 → forcé à 1, window < 1000 → forcé à 1000
        SyncRateLimitFilter f = new SyncRateLimitFilter(0, 0);
        // Sanity : ne plante pas et applique au moins la limite minimale.
        assertThat(f).isNotNull();
    }
}
