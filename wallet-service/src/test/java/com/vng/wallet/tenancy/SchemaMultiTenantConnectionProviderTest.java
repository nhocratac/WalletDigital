package com.vng.wallet.tenancy;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SP5 Task 4 review fix: the fail-closed path must throw AND release the borrowed connection,
 * otherwise every forgot-tenant DB op leaks one Hikari connection until the pool is exhausted.
 *
 * <p>Uses a hand-rolled {@link Connection} JDK proxy (Mockito's inline mock maker cannot
 * instrument the JDBC interfaces on this JVM) so we can observe close().
 */
class SchemaMultiTenantConnectionProviderTest {

    @Test
    void getConnection_failClosed_throws_and_closes_the_borrowed_connection() throws SQLException {
        AtomicBoolean closed = new AtomicBoolean(false);
        Connection connection = fakeConnection(closed);
        DataSource dataSource = singleConnectionDataSource(connection);

        SchemaMultiTenantConnectionProvider provider =
                new SchemaMultiTenantConnectionProvider(dataSource);

        assertThatThrownBy(() -> provider.getConnection(TenantSchemas.EMPTY_SENTINEL))
                .isInstanceOf(SQLException.class);

        // The bug: switchSchema throws AFTER borrow(); Hibernate never calls releaseConnection()
        // for a connection getConnection didn't return → leak. The fix must close it before rethrow.
        assertThat(closed.get())
                .as("borrowed connection must be closed when the fail-closed switch throws")
                .isTrue();
    }

    /** A Connection proxy: setCatalog(__no_tenant__) throws; getSchema returns PUBLIC; close() flips the flag. */
    private static Connection fakeConnection(AtomicBoolean closed) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                case "getSchema", "getCatalog" -> {
                    return "PUBLIC";
                }
                case "setCatalog" -> {
                    if (TenantSchemas.EMPTY_SENTINEL.equals(args[0])) {
                        throw new SQLException("Unknown database " + TenantSchemas.EMPTY_SENTINEL);
                    }
                    return null;
                }
                case "setSchema" -> {
                    return null;
                }
                case "toString" -> {
                    return "fakeConnection";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> {
                    return null;
                }
            }
        };
        return (Connection) Proxy.newProxyInstance(
                SchemaMultiTenantConnectionProviderTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static DataSource singleConnectionDataSource(Connection connection) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return connection;
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return null;
            }
        };
    }
}
