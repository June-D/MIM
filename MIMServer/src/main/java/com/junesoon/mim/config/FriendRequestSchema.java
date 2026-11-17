package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐好友申请表。
 *
 * <p>本地库不一定重新执行过 sql 脚本。缺表时申请接口会直接 500，
 * 所以启动时用 CREATE TABLE IF NOT EXISTS 补上。已有表不会被改写。
 */
@Component
public class FriendRequestSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FriendRequestSchema.class);

    private static final String DDL = ""
        + "CREATE TABLE IF NOT EXISTS t_friend_request ("
        + "  id bigint NOT NULL AUTO_INCREMENT,"
        + "  from_user varchar(64) NOT NULL,"
        + "  to_user varchar(64) NOT NULL,"
        + "  status tinyint NOT NULL DEFAULT 0,"
        + "  created_at bigint NOT NULL,"
        + "  updated_at bigint NOT NULL,"
        + "  PRIMARY KEY (id),"
        + "  UNIQUE KEY uk_pair (from_user, to_user),"
        + "  KEY idx_inbox (to_user, status)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final JdbcTemplate jdbcTemplate;

    public FriendRequestSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(DDL);
        log.info("好友申请表已就绪");
    }
}
