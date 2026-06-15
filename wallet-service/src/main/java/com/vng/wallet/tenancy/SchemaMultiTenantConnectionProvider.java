package com.vng.wallet.tenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SP5 Task 4 (Quyết định khoá #1, T10): ONE shared pool, schema switched on the borrowed connection.
 *
 * <p>{@link #getConnection(Object)} borrows from the single application {@link DataSource} and runs
 * {@code SET SCHEMA tenant_<id>} so this unit of work sees ONLY that tenant's schema — isolation by
 * construction, even for a query that forgot its WHERE. {@link #releaseConnection(Object, Connection)}
 * resets the connection to the pool's default schema BEFORE returning it, so a pooled thread/connection
 * cannot leak the previous tenant's schema to the next borrower (the connection-level twin of T4).
 *
 * <p>Two sentinels (see {@link TenantSchemas}):
 * <ul>
 *   <li>{@link TenantSchemas#DEFAULT_SENTINEL} / {@link #getAnyConnection()} (bootstrap, before any
 *       tenant) — leave the connection on the pool's default schema (no switch).</li>
 *   <li>{@link TenantSchemas#EMPTY_SENTINEL} — empty TenantContext: {@code SET SCHEMA __no_tenant__}
 *       on a non-existent schema throws → FAIL-CLOSED.</li>
 * </ul>
 */
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    /**
     * The pool's default schema, captured once from the first borrowed connection. We reset to THIS
     * on release so a pooled connection that was switched to {@code tenant_x} does not carry that
     * schema to its next borrower (Hikari does not reset schema on return by default).
     */
    private volatile String defaultSchema;

    public SchemaMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection borrow() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (defaultSchema == null) {
            // Lazily capture the pool's neutral schema (the JDBC URL's default DB on MySQL / PUBLIC on H2).
            synchronized (this) {
                if (defaultSchema == null) {
                    defaultSchema = connection.getSchema();
                }
            }
        }
        return connection;
    }

    /** Bootstrap / metadata connection — no tenant yet, stay on the pool default schema. */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return borrow();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        resetAndClose(connection);
    }

    @Override
    public Connection getConnection(String schemaIdentifier) throws SQLException {
        Connection connection = borrow();
        try {
            if (!TenantSchemas.DEFAULT_SENTINEL.equals(schemaIdentifier)) {
                // Real tenant OR the empty sentinel: switch schema. For __no_tenant__ this throws on
                // a non-existent schema — fail-closed, as required.
                switchSchema(connection, schemaIdentifier);
            }
        } catch (SQLException | RuntimeException e) {
            // The switch threw BEFORE we returned the connection to Hibernate, so Hibernate will
            // never call releaseConnection() for it. Close the borrowed connection here, otherwise
            // every fail-closed (forgot-tenant) op orphans a pooled connection → pool exhaustion.
            // Fail-closed semantics are preserved: we still rethrow, never returning a connection.
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String schemaIdentifier, Connection connection) throws SQLException {
        resetAndClose(connection);
    }

    /**
     * Reset the connection to the pool's neutral default schema BEFORE returning it to the pool, so
     * the next borrower never inherits this tenant's schema (connection-level no-leak, twin of T4).
     */
    private void resetAndClose(Connection connection) throws SQLException {
        try {
            if (defaultSchema != null && !connection.isClosed()) {
                switchSchema(connection, defaultSchema);
            }
        } finally {
            connection.close();
        }
    }

    /**
     * Point the connection at {@code schema}. We set BOTH schema and catalog because MySQL's JDBC
     * driver models a "schema" as a catalog (database) — {@link Connection#setSchema(String)} alone
     * is a no-op there; {@link Connection#setCatalog(String)} is the one that actually switches the
     * active database. H2 honours {@code setSchema}. Setting both keeps one code path portable, and a
     * non-existent schema (the empty sentinel) throws on at least one of them → fail-closed.
     */
    private void switchSchema(Connection connection, String schema) throws SQLException {
        connection.setCatalog(schema);
        connection.setSchema(schema);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(@SuppressWarnings("rawtypes") Class unwrapType) {
        return MultiTenantConnectionProvider.class.equals(unwrapType)
                || SchemaMultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (T) this;
        }
        throw new UnknownUnwrapTypeException(unwrapType);
    }

    /** Local copy to avoid pulling the Hibernate exception type into the import list of callers. */
    static final class UnknownUnwrapTypeException extends RuntimeException {
        UnknownUnwrapTypeException(Class<?> type) {
            super("Cannot unwrap to " + type);
        }
    }
}
