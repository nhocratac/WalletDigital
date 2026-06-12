package com.vng.wallet.infrastructure.kyc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/** Cache CHỈ entry APPROVED (D6). */
public class KycStatusCache {
    private final Cache<String, Boolean> approved;

    public KycStatusCache(long ttlSeconds) {
        this.approved = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).maximumSize(10_000).build();
    }

    public boolean isApproved(String userId) { return approved.getIfPresent(userId) != null; }
    public void markApproved(String userId) { approved.put(userId, Boolean.TRUE); }
    public void evict(String userId) { approved.invalidate(userId); }   // naturally idempotent (D9)
}
