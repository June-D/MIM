package com.junesoon.mim.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 集成测试基类：真实 Spring 上下文 + 真实 MyBatis + 真实 MySQL 方言。
 *
 * <p>每个具体测试类通过 {@link #prepare(String)} 使用自己的库。
 * 这样并行跑测试类时，一方的 {@code truncateAll} 不会清掉另一方的数据。
 * {@code @DynamicPropertySource} 必须写在具体类上，并捕获该类自己的
 * {@link Database}，不能共用基类里的一个静态地址。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
public abstract class IntegrationTestBase {

    private static final String DEFAULT_URL =
        "jdbc:mysql://127.0.0.1:3306/mim_test?serverTimezone=Asia/Shanghai"
            + "&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true";
    private static final String DEFAULT_USERNAME = "root";
    private static final String DEFAULT_PASSWORD = "";

    /**
     * 一个测试类独占的库。
     */
    public static final class Database {
        final String url;
        final String username;
        final String password;
        final boolean available;

        Database(String url, String username, String password, boolean available) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.available = available;
        }
    }

    /**
     * 在 {@code MIM_TEST_DB_URL} 指向的实例上创建 {@code <原库名>_<suffix>} 并建表。
     * 连不上时返回不可用的库，由 {@link #requireReady(Database)} 决定跳过还是失败。
     */
    public static Database prepare(String suffix) {
        if (suffix == null || !suffix.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("库名后缀不合法");
        }
        String source = envOrDefault("MIM_TEST_DB_URL", DEFAULT_URL);
        String username = envOrDefault("MIM_TEST_DB_USERNAME", DEFAULT_USERNAME);
        String password = envOrDefault("MIM_TEST_DB_PASSWORD", DEFAULT_PASSWORD);
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String schema = databaseName(source) + "_" + suffix;
            if (!schema.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("库名不合法：" + schema);
            }
            try (Connection connection = DriverManager.getConnection(source, username, password);
                Statement statement = connection.createStatement()) {
                // 测试库按类独占；每次运行重建，避免旧表结构缺少新列
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
                statement.execute("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
            String target = withDatabase(source, schema);
            createSchema(target, username, password);
            return new Database(target, username, password, true);
        } catch (Exception e) {
            System.err.println("[集成测试] 准备独立库 " + suffix + " 失败，将跳过：" + e.getMessage());
            return new Database(source, username, password, false);
        }
    }

    public static void register(DynamicPropertyRegistry registry, Database database) {
        registry.add("spring.datasource.druid.url", () -> database.url);
        registry.add("spring.datasource.druid.username", () -> database.username);
        registry.add("spring.datasource.druid.password", () -> database.password);
    }

    public static void requireReady(Database database) {
        if ("true".equalsIgnoreCase(System.getenv("MIM_TEST_DB_REQUIRED"))) {
            assertTrue(database.available,
                "MIM_TEST_DB_REQUIRED=true 但数据库不可用，集成测试无法执行。目标：" + database.url);
        }
        Assumptions.assumeTrue(database.available,
            () -> "MySQL 不可用，跳过集成测试。请设置 MIM_TEST_DB_URL 或启动 "
                + "127.0.0.1:3306 上的 mim_test 库。当前目标：" + database.url);
    }

    protected abstract Database database();

    /**
     * 清空业务数据但保留表结构。子类在 {@code @BeforeEach} 调用。
     */
    protected void truncateAll() throws Exception {
        List<String> tables = new ArrayList<>();
        tables.add("t_message");
        tables.add("t_one_time_prekey");
        tables.add("t_signed_prekey");
        tables.add("t_user_key");
        tables.add("t_device");
        tables.add("t_chat_users");
        tables.add("t_chat_group");
        tables.add("t_register_users");
        tables.add("t_friend_request");
        tables.add("t_conversation_clear");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    protected Connection open() throws SQLException {
        Database database = database();
        return DriverManager.getConnection(database.url, database.username, database.password);
    }

    /**
     * 执行一条更新并返回影响行数，供断言直接验证 SQL 语义。
     */
    protected int execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String databaseName(String url) {
        int scheme = url.indexOf("//");
        int slash = url.indexOf('/', scheme + 2);
        if (slash < 0) {
            throw new IllegalStateException("数据库地址缺少库名：" + url);
        }
        int query = url.indexOf('?', slash);
        return query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
    }

    private static String withDatabase(String url, String name) {
        int scheme = url.indexOf("//");
        int slash = url.indexOf('/', scheme + 2);
        int query = url.indexOf('?', slash);
        String prefix = url.substring(0, slash + 1);
        String suffix = query < 0 ? "" : url.substring(query);
        return prefix + name + suffix;
    }

    private static void createSchema(String url, String username, String password) throws Exception {
        Path sqlDir = locateSqlDirectory();
        List<String> statements = new ArrayList<>();
        statements.addAll(readSql(sqlDir.resolve("chat.sql")));
        statements.addAll(readSql(sqlDir.resolve("e2ee.sql")));
        try (Connection connection = DriverManager.getConnection(url, username, password);
            Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static Path locateSqlDirectory() {
        String[] candidates = {"sql", "../sql", "./MIMServer/sql"};
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path) && Files.exists(path.resolve("e2ee.sql"))) {
                return path;
            }
        }
        throw new IllegalStateException("找不到 SQL 目录，集成测试需要 MIMServer/sql 下的建表脚本");
    }

    private static List<String> readSql(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        return statements;
    }
}
