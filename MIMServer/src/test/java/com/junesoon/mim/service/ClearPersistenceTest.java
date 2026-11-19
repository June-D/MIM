package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.responses.ClearResult;
import com.junesoon.mim.service.impl.MessageServiceImpl;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 清除必须落在真实 SQL 上。接口上的 default 方法会被 MyBatis 直接调用，XML 不会执行，
 * 表现就是清除点一直是 0，重新登录后消息还在，对方也看得到。
 */
class ClearPersistenceTest {

    private static final String URL =
        "jdbc:mysql://127.0.0.1:13306/mim_clear_probe?serverTimezone=Asia/Shanghai"
            + "&useUnicode=true&characterEncoding=utf8";

    @AfterEach
    void dropProbe() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:13306/?serverTimezone=Asia/Shanghai", "root", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop database if exists mim_clear_probe");
            }
        } catch (Exception ignored) {
            // 库没建起来时不需要清理
        }
    }

    @Test
    @DisplayName("清除双方后清除点是最新消息，两边历史为空，密文被抹掉")
    void clearBothHidesHistoryAndWipesCiphertext() throws Exception {
        Assumptions.assumeTrue(prepareSchema(), "本地 MySQL 13306 不可用");
        long id = insertMessage();
        SqlSessionFactory factory = sessionFactory();
        try (SqlSession session = factory.openSession(true)) {
            MessageServiceImpl service = new MessageServiceImpl(
                session.getMapper(MessageMapper.class),
                session.getMapper(ConversationClearMapper.class),
                null, null, null, null);
            ClearResult result = service.clearConversation("probea", "probeb", true,
                java.util.Collections.singletonList("tagAAAAAAAAAAAAAAAAAAAAAAAA1"));
            assertEquals(id, result.getClearedBeforeId());
            assertTrue(service.history("probea", "probeb", null, 20, null,
                java.util.Collections.singletonList("tagAAAAAAAAAAAAAAAAAAAAAAAA1")).isEmpty());
            assertTrue(service.history("probeb", "probea", null, 20, null,
                java.util.Collections.singletonList("tagAAAAAAAAAAAAAAAAAAAAAAAA1")).isEmpty());
        }
        assertNull(ciphertext());
    }

    private static boolean prepareSchema() {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:13306/?serverTimezone=Asia/Shanghai", "root", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create database if not exists mim_clear_probe "
                    + "character set utf8mb4 collate utf8mb4_general_ci");
            }
        } catch (Exception e) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection(URL, "root", "");
            Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists t_message ("
                + "id bigint not null auto_increment primary key,"
                + "client_msg_id varchar(64) not null,"
                + "conversation_tag varchar(64) null,"
                + "to_user varchar(64) not null,"
                + "target_device_id varchar(64) not null,"
                + "alg varchar(64) not null,"
                + "iv varchar(64) null,"
                + "ciphertext mediumtext null,"
                + "ratchet_public varchar(128) null,"
                + "previous_chain_length int null,"
                + "message_number int null,"
                + "sender_identity_key text null,"
                + "sender_ephemeral_key varchar(128) null,"
                + "sender_used_opk tinyint null,"
                + "consumed_opk_id int null,"
                + "recipient_spk_id int null,"
                + "padding tinyint not null default 0,"
                + "sealed tinyint not null default 0,"
                + "session_id varchar(64) null,"
                + "sender_key_version int null,"
                + "recipient_key_version int null,"
                + "client_time bigint not null,"
                + "server_time bigint not null,"
                + "delivered tinyint not null default 0,"
                + "read_flag tinyint not null default 0,"
                + "recalled tinyint not null default 0,"
                + "recalled_at bigint null,"
                + "recall_token_hash varchar(64) null,"
                + "sync_from_device varchar(64) null,"
                + "sync_original_from varchar(64) null,"
                + "sync_original_to varchar(64) null,"
                + "sync_original_device varchar(64) null,"
                + "sync_signature text null"
                + ")");
            statement.execute("create table if not exists t_conversation_clear ("
                + "username varchar(64) not null,"
                + "peer varchar(64) not null,"
                + "cleared_before_id bigint not null,"
                + "updated_at bigint not null,"
                + "primary key (username, peer)"
                + ")");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long insertMessage() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "root", "");
            PreparedStatement statement = connection.prepareStatement(
                "insert into t_message (client_msg_id, conversation_tag, to_user,"
                    + " target_device_id, alg, iv, ciphertext, client_time, server_time)"
                    + " values ('probe-msg', 'tagAAAAAAAAAAAAAAAAAAAAAAAA1', 'probeb', 'dev',"
                    + " 'ALG', 'iv', 'secret', 1, 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static String ciphertext() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "root", "");
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("select ciphertext from t_message")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static SqlSessionFactory sessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
            "com.mysql.cj.jdbc.Driver", URL, "root", "");
        Configuration configuration = new Configuration(
            new Environment("probe", new JdbcTransactionFactory(), dataSource));
        load(configuration, "mapper/MessageMapper.xml");
        load(configuration, "mapper/ConversationClearMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void load(Configuration configuration, String resource) throws Exception {
        XMLMapperBuilder parser = new XMLMapperBuilder(
            Resources.getResourceAsStream(resource), configuration, resource,
            configuration.getSqlFragments());
        parser.parse();
    }
}
