package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐一次性恢复码表；旧库无需手工迁移。
 */
@Component
public class RecoveryCodeSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryCodeSchema.class);

    private static final String DDL = ""
        + "CREATE TABLE IF NOT EXISTS t_recovery_code ("
        + "  id bigint NOT NULL AUTO_INCREMENT,"
        + "  username varchar(64) NOT NULL,"
        + "  code_hash varchar(100) NOT NULL,"
        + "  used tinyint NOT NULL DEFAULT 0,"
        + "  created_at bigint NOT NULL,"
        + "  used_at bigint NULL,"
        + "  PRIMARY KEY (id),"
        + "  KEY idx_user_unused (username, used)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    private final JdbcTemplate jdbcTemplate;

    public RecoveryCodeSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(DDL);
        log.info("一次性恢复码表已就绪");
    }
}
