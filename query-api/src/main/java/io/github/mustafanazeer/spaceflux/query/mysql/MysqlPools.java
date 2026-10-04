package io.github.mustafanazeer.spaceflux.query.mysql;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * One pool per database user (ADR 0010). Neither is primary, so injecting a {@code DataSource},
 * {@code JdbcClient} or transaction manager without naming the pool fails at startup, and a
 * {@code @Transactional} without a qualifier fails on its first call.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MysqlProperties.class)
class MysqlPools {

    // The two consumers each apply one record at a time; the API serves a handful of dashboard viewers.
    private static final int CONSUMER_POOL_SIZE = 2;
    private static final int API_POOL_SIZE = 4;

    @Bean(destroyMethod = "close")
    HikariDataSource consumerDataSource(MysqlProperties mysql, ConfigurableEnvironment env) {
        return pool("consumer", mysql, mysql.consumer(), env, CONSUMER_POOL_SIZE);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource apiDataSource(MysqlProperties mysql, ConfigurableEnvironment env) {
        return pool("api", mysql, mysql.api(), env, API_POOL_SIZE);
    }

    @Bean
    JdbcClient consumerJdbcClient(@Qualifier("consumerDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcClient apiJdbcClient(@Qualifier("apiDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager consumerTransactionManager(@Qualifier("consumerDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    JdbcTransactionManager apiTransactionManager(@Qualifier("apiDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    private static HikariDataSource pool(String name, MysqlProperties mysql, MysqlProperties.Account account,
            ConfigurableEnvironment env, int size) {
        // HikariConfig's constructor loads this file, and its dataSource entries could override the URL's settings.
        if (System.getProperty("hikaricp.configurationFile") != null) {
            throw new IllegalStateException("hikaricp.configurationFile is set; the pool settings come only from "
                    + "spaceflux.mysql");
        }
        String password = SecretFiles.password(env, name, account.username());
        HikariConfig config = new HikariConfig();
        config.setPoolName("mysql-" + name);
        config.setJdbcUrl(mysql.jdbcUrl());
        config.setUsername(account.username());
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            GrantCheck.verify(name, dataSource, GrantCheck.expected(name, account.username(), mysql.database()));
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return dataSource;
    }
}
