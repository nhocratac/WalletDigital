package com.vng.wallet.tenancy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * SP5 Task 3 (T5): the TENANT (default) persistence unit — the first of the two persistence
 * concerns, and the one Task 4 will route per-tenant.
 *
 * <p>Declared explicitly because once {@link com.vng.wallet.tenancy.master.MasterPersistenceConfig}
 * adds a second {@code @EnableJpaRepositories}/EMF, Spring Boot's JPA auto-config backs off — so we
 * must own BOTH units. This EMF stays {@code @Primary}: it scans the wallet/ledger/order entities in
 * {@code infrastructure.persistence} and is what every existing SP1–SP4 component already injects.
 * Behavior is preserved (still one DataSource, ddl-auto=none, default Flyway = tenant location).
 *
 * <p>Routing (Hibernate SCHEMA multitenancy) is wired onto THIS EMF in Task 4; here it is a plain
 * non-routed EMF on the default schema, identical to the auto-configured one it replaces.
 */
@Configuration
@EnableConfigurationProperties(JpaProperties.class)
@EnableJpaRepositories(
        basePackages = "com.vng.wallet.infrastructure.persistence",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager")
public class TenantPersistenceConfig {

    @Primary
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            DataSource dataSource,
            JpaProperties jpaProperties) {
        Map<String, Object> props = new HashMap<>(jpaProperties.getProperties());
        props.put("hibernate.hbm2ddl.auto", "none");
        return builder
                .dataSource(dataSource)
                .packages("com.vng.wallet.infrastructure.persistence")
                .persistenceUnit("tenant")
                .properties(props)
                .build();
    }

    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("entityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
