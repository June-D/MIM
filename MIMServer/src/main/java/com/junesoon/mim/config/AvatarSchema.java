package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐头像表。
 *
 * <p>头像文件在磁盘，表只保存版本号与更新时间。客户端可以用版本号做缓存失效，
 * 服务端不需要解释图片内容。旧库首次启动会自动补表，新装库不用手工执行 SQL。
 */
@Component
public class AvatarSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AvatarSchema.class);

    private static final String DDL = ""
        + "CREATE TABLE IF NOT EXISTS t_avatar ("
        + "  username varchar(64) NOT NULL,"
        + "  version int NOT NULL,"
        + "  updated_at bigint NOT NULL,"
        + "  PRIMARY KEY (username)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    private final JdbcTemplate jdbcTemplate;

    public AvatarSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(DDL);
        log.info("头像表已就绪");
    }
}
