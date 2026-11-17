package com.junesoon.mim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 给已有的消息表补上设备间转发标记。新库的建表脚本里已经有这一列。
 */
@Component
public class MessageSyncSchema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MessageSyncSchema.class);

    private final JdbcTemplate jdbcTemplate;

    public MessageSyncSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.COLUMNS "
                + "where TABLE_SCHEMA = database() and TABLE_NAME = 't_message' "
                + "and COLUMN_NAME = 'sync_from_device'",
            Integer.class);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute("alter table t_message add column sync_from_device varchar(64) null");
        log.info("消息表已补上 sync_from_device");
    }
}
