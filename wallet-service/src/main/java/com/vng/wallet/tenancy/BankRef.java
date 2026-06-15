package com.vng.wallet.tenancy;

import java.util.UUID;

/**
 * SP5 Task 7 (T9): a withdrawal's bank reference, with the owning tenant EMBEDDED in it.
 *
 * <p>Why: the bank settlement webhook (fast path) arrives on a bank-callback request that carries NO
 * {@code X-Tenant-Id} header — the bank doesn't know about our tenants. Yet the webhook must look the
 * order up by bankRef, and that lookup is ROUTED (schema-per-tenant) so it needs a {@link TenantContext}
 * pointed at the right schema. Encoding the tenant into the bankRef lets the webhook recover it
 * deterministically and set the context before the routed lookup (Quyết định khoá Task 7: "bankRef
 * định dạng {@code <tenant>-...} hoặc tra registry" — we encode it, no extra registry round-trip).
 *
 * <p>Format: {@code wd.<tenant>.<uuid>}. The {@code .} delimiter is chosen because the {@code wd}
 * prefix and the UUID never contain it, so the tenant segment (which MAY itself contain dashes) is
 * the single middle field between the first and last dots. The tenant is still UNIQUE per call thanks
 * to the random UUID suffix (preserving the SP4 idempotency-via-bankRef invariant, E7).
 *
 * <p>Legacy/foreign refs that do not match the format (e.g. SP4's {@code wd-<uuid>} or an unknown
 * bank ref) yield {@code null} from {@link #tenantOf} — the webhook then falls back to whatever
 * context is already set (the single-schema baseline), never guessing a tenant.
 */
public final class BankRef {

    private static final String PREFIX = "wd";
    private static final char SEP = '.';

    private BankRef() {
    }

    /** Mint a new, unique bankRef for {@code tenantId} (the value reused on every bank retry, E7). */
    public static String create(String tenantId) {
        return PREFIX + SEP + tenantId + SEP + UUID.randomUUID();
    }

    /**
     * Recover the tenant encoded in {@code bankRef}, or {@code null} if it is not a tenant-encoded ref
     * (legacy {@code wd-...}, unknown, or null). The tenant is everything between the first and last
     * {@code .} so tenant ids containing dashes round-trip correctly.
     */
    public static String tenantOf(String bankRef) {
        if (bankRef == null) {
            return null;
        }
        int first = bankRef.indexOf(SEP);
        int last = bankRef.lastIndexOf(SEP);
        if (first <= 0 || last <= first) {
            return null;
        }
        if (!PREFIX.equals(bankRef.substring(0, first))) {
            return null;
        }
        String tenant = bankRef.substring(first + 1, last);
        return tenant.isBlank() ? null : tenant;
    }
}
