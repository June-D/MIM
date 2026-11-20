package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐加密媒体表。
 *
 * <p>媒体文件在磁盘，表只保存 id、上传者、大小与时间。
 * 新装库无需手工执行 SQL；旧库首次启动也会自动补表。
 */
@Component
public class MediaSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaSchema.class);

    private static final String DDL = ""
        + "CREATE TABLE IF NOT EXISTS t_media ("
        + "  id varchar(64) NOT NULL,"
        + "  owner varchar(64) NOT NULL,"
        + "  size bigint NOT NULL,"
        + "  crt_time bigint NOT NULL,"
        + "  PRIMARY KEY (id),"
        + "  KEY idx_media_owner (owner)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    private final JdbcTemplate jdbcTemplate;

    public MediaSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(DDL);
        log.info("加密媒体表已就绪");
    }
}
