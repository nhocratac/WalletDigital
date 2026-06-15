package com.vng.wallet.tenancy.master;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * SP5 Task 3 (T5): the MASTER persistence unit — the second of the two persistence concerns.
 *
 * <ul>
 *   <li><b>Non-routed.</b> It scans ONLY {@link TenantRegistry} and pins every connection at the
 *       fixed {@code master} schema (hibernate.default_schema/catalog). It does NOT go through the
 *       tenant routing connection provider (Task 4) — so it is readable with an EMPTY TenantContext,
 *       which is exactly what routing needs (chicken-egg: look up tenantId→schema first).</li>
 *   <li><b>Own Flyway.</b> Migrates {@code db/migration/master} into a dedicated {@code master}
 *       schema. Done imperatively (NOT as a {@code Flyway} @Bean) on purpose: a {@code Flyway}-typed
 *       bean would trip Spring Boot's {@code @ConditionalOnMissingBean(Flyway.class)} and DISABLE the
 *       app's auto-configured tenant Flyway. Keeping master migration off the bean type leaves the
 *       default tenant Flyway (db/migration/tenant) fully intact — Task 1 behavior preserved.</li>
 *   <li><b>Own repositories.</b> {@code tenancy.master} repos bind here; the tenant/default EMF
 *       (see {@link com.vng.wallet.tenancy.TenantPersistenceConfig}) stays @Primary for the rest.</li>
 * </ul>
 *
 * <p>Shares the application {@link DataSource} (one pool, T10) — only the schema differs.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.vng.wallet.tenancy.master",
        entityManagerFactoryRef = "masterEntityManagerFactory",
        transactionManagerRef = "masterTransactionManager")
public class MasterPersistenceConfig {

    /** JPA persistence-unit name; referenced by @PersistenceContext(unitName=...) for INSERTs. */
    public static final String MASTER_UNIT = "master";
    public static final String MASTER_SCHEMA = "master";

    /**
     * Imperatively create + migrate the master schema. Returns a marker bean (NOT typed
     * {@code Flyway}) the master EMF can {@code @DependsOn}, so the table exists before Hibernate
     * uses it — without disabling Boot's default tenant Flyway.
     */
    @Bean
    public MasterSchemaInitialized masterSchemaInitializer(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(MASTER_SCHEMA)
                .createSchemas(true)
                .defaultSchema(MASTER_SCHEMA)
                .locations("classpath:db/migration/master")
                .baselineOnMigrate(true)
                .load()
                .migrate();
        return new MasterSchemaInitialized();
    }

    /** Marker that the master schema has been migrated (dependency anchor for the master EMF). */
    public static final class MasterSchemaInitialized {
    }

    @Bean
    @DependsOn("masterSchemaInitializer")
    public LocalContainerEntityManagerFactoryBean masterEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            DataSource dataSource) {
        Map<String, Object> props = new HashMap<>();
        // MySQL maps "schema" to a database/catalog; H2 uses schema. Set BOTH so the master EMF
        // qualifies tables as master.tenant_registry regardless of the connection's default DB.
        props.put("hibernate.default_schema", MASTER_SCHEMA);
        props.put("hibernate.default_catalog", MASTER_SCHEMA);
        props.put("hibernate.hbm2ddl.auto", "none");
        return builder
                .dataSource(dataSource)
                .packages(TenantRegistry.class)
                .persistenceUnit(MASTER_UNIT)
                .properties(props)
                .build();
    }

    @Bean
    public PlatformTransactionManager masterTransactionManager(
            @Qualifier("masterEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
