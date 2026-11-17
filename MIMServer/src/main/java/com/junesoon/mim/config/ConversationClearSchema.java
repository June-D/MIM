package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐会话清除表。清除点存在数据库里，换实例、重启之后仍然生效。
 */
@Component
public class ConversationClearSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConversationClearSchema.class);

    private static final String DDL = ""
        + "CREATE TABLE IF NOT EXISTS t_conversation_clear ("
        + "  username varchar(64) NOT NULL,"
        + "  peer varchar(64) NOT NULL,"
        + "  cleared_before_id bigint NOT NULL,"
        + "  updated_at bigint NOT NULL,"
        + "  PRIMARY KEY (username, peer)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    private final JdbcTemplate jdbcTemplate;

    public ConversationClearSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(DDL);
        log.info("会话清除表已就绪");
    }
}
