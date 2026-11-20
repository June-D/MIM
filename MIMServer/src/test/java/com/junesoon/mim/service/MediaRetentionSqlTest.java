package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.mapper.MediaMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保留期巡检必须是真实 SQL：XML 里的列名、类型或分页写错，
 * 单测用假 mapper 是发现不了的。
 */
class MediaRetentionSqlTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String URL =
        "jdbc:mysql://127.0.0.1:13306/mim_media_probe?serverTimezone=Asia/Shanghai"
            + "&useUnicode=true&characterEncoding=utf8";

    @AfterEach
    void dropProbe() {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:13306/?serverTimezone=Asia/Shanghai", "root", "");
            Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists mim_media_probe");
        } catch (Exception ignored) {
            // 库没建起来时不需要清理
        }
    }

    @Test
    @DisplayName("按 crt_time 取过期记录并删除，未过期的不受影响")
    void selectAndDeleteExpired() throws Exception {
        Assumptions.assumeTrue(prepareSchema(), "本地 MySQL 13306 不可用");
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(URL, "root", "");
            Statement statement = connection.createStatement()) {
            statement.execute("insert into t_media (id, owner, size, crt_time) values"
                + " ('old-media-id-0001', 'alice', 10, " + (now - 40 * DAY_MS) + "),"
                + " ('new-media-id-0002', 'alice', 20, " + now + ")");
        }

        try (SqlSession session = sessionFactory().openSession(true)) {
            MediaMapper mapper = session.getMapper(MediaMapper.class);

            List<String> expired = mapper.selectIdsOlderThan(now - 30 * DAY_MS, 100);
            assertEquals(List.of("old-media-id-0001"), expired);

            assertEquals(1, mapper.deleteById("old-media-id-0001"));
            assertNull(mapper.selectById("old-media-id-0001"));
            MediaObject fresh = mapper.selectById("new-media-id-0002");
            assertTrue(fresh != null && fresh.getSize() == 20, "未过期记录必须保留");
        }
    }

    private static boolean prepareSchema() {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:13306/?serverTimezone=Asia/Shanghai", "root", "");
            Statement statement = connection.createStatement()) {
            statement.execute("create database if not exists mim_media_probe "
                + "character set utf8mb4 collate utf8mb4_general_ci");
        } catch (Exception e) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection(URL, "root", "");
            Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists t_media ("
                + "id varchar(64) not null primary key,"
                + "owner varchar(64) not null,"
                + "size bigint not null,"
                + "crt_time bigint not null"
                + ")");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static SqlSessionFactory sessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
            "com.mysql.cj.jdbc.Driver", URL, "root", "");
        Configuration configuration = new Configuration(
            new Environment("probe", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/MediaMapper.xml";
        XMLMapperBuilder parser = new XMLMapperBuilder(
            Resources.getResourceAsStream(resource), configuration, resource,
            configuration.getSqlFragments());
        parser.parse();
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
