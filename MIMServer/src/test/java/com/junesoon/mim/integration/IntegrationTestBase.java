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
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类：真实 Spring 上下文 + 真实 MyBatis + 真实 MySQL 方言。
 *
 * <h3>为什么不用 H2</h3>
 * Mapper 依赖若干 MySQL 专有语法：{@code ON DUPLICATE KEY UPDATE}、
 * {@code INSERT IGNORE}、{@code SELECT ... FOR UPDATE}、分组去重子查询。
 * H2 的 MySQL 兼容模式对这些支持程度不一，用它测出来的"通过"会误导人。
 * 宁可跳过，也不要假绿。
 *
 * <h3>时序约束</h3>
 * 应用启动时 {@code AuthServerRunner} 会读 {@code t_rsa_key} 生成 JWT 密钥，
 * 因此<b>表结构必须在 Spring 上下文启动前就位</b>。
 * 这里的做法是：在静态初始化块（早于 {@code @DynamicPropertySource}）
 * 建库建表，然后在每个测试方法前只做 {@code TRUNCATE} 清数据。
 *
 * <h3>连接配置</h3>
 * 按以下顺序取（任一可用即跑，全部不可用则<b>跳过</b>而非失败）：
 * <ol>
 *   <li>环境变量 {@code MIM_TEST_DB_URL} / {@code MIM_TEST_DB_USERNAME} / {@code MIM_TEST_DB_PASSWORD}</li>
 *   <li>默认 {@code jdbc:mysql://127.0.0.1:3306/mim_test}，root 空口令</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
public abstract class IntegrationTestBase {

    private static final String DEFAULT_URL =
        "jdbc:mysql://127.0.0.1:3306/mim_test?serverTimezone=Asia/Shanghai"
            + "&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true";
    private static final String DEFAULT_USERNAME = "root";
    private static final String DEFAULT_PASSWORD = "";

    protected static String dbUrl;
    protected static String dbUsername;
    protected static String dbPassword;

    /**
     * 数据库是否可用。为 false 时所有子类测试被 JUnit 跳过。
     */
    protected static boolean databaseAvailable;

    /**
     * 静态初始化：在 Spring 上下文启动前把表结构准备好。
     *
     * <p>这段代码跑在任何 Spring 逻辑之前，是 {@code AuthServerRunner}
     * 能读到 {@code t_rsa_key} 的前提。
     */
    static {
        loadDatabaseConfig();
        databaseAvailable = probe();
        if (databaseAvailable) {
            try {
                createSchemaIfNeeded();
            } catch (Exception e) {
                // 建表失败视为不可用：宁可跳过，也不要在上下文启动时抛一堆异常
                databaseAvailable = false;
                System.err.println("[集成测试] 初始化表结构失败，将跳过集成测试：" + e.getMessage());
            }
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.druid.url", () -> dbUrl);
        registry.add("spring.datasource.druid.username", () -> dbUsername);
        registry.add("spring.datasource.druid.password", () -> dbPassword);
    }

    @BeforeAll
    static void requireDatabase() {
        // CI 里设置 MIM_TEST_DB_REQUIRED=true：数据库不可用时明确失败，
        // 而不是被 Assumptions 跳过导致"0 个测试 + BUILD SUCCESS"的假绿。
        if ("true".equalsIgnoreCase(System.getenv("MIM_TEST_DB_REQUIRED"))) {
            assertTrue(databaseAvailable,
                "MIM_TEST_DB_REQUIRED=true 但数据库不可用，集成测试无法执行。目标：" + dbUrl);
        }
        Assumptions.assumeTrue(databaseAvailable,
            () -> "MySQL 不可用，跳过集成测试。请设置 MIM_TEST_DB_URL 或启动 "
                + "127.0.0.1:3306 上的 mim_test 库。当前目标：" + dbUrl);
    }

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
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    protected Connection open() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
    }

    /**
     * 执行一条更新并返回影响行数，供断言直接验证 SQL 语义。
     */
    protected int execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    // ==================== 内部 ====================

    private static void loadDatabaseConfig() {
        dbUrl = envOrDefault("MIM_TEST_DB_URL", DEFAULT_URL);
        dbUsername = envOrDefault("MIM_TEST_DB_USERNAME", DEFAULT_USERNAME);
        dbPassword = envOrDefault("MIM_TEST_DB_PASSWORD", DEFAULT_PASSWORD);
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean probe() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            return connection.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 建表（幂等）。脚本里用的是 CREATE TABLE IF NOT EXISTS，
     * 重复执行安全，因此不需要先判断存在性。
     */
    private static void createSchemaIfNeeded() throws Exception {
        Path sqlDir = locateSqlDirectory();
        List<String> statements = new ArrayList<>();
        statements.addAll(readSql(sqlDir.resolve("chat.sql")));
        statements.addAll(readSql(sqlDir.resolve("e2ee.sql")));
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
            Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * 定位 sql 目录：测试从 MIMServer 目录运行，SQL 在 ./sql 或 ../sql。
     */
    private static Path locateSqlDirectory() {
        String[] candidates = {"sql", "../sql", "./MIMServer/sql"};
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path) && Files.exists(path.resolve("e2ee.sql"))) {
                return path;
            }
        }
        throw new IllegalStateException(
            "找不到 SQL 目录，集成测试需要 MIMServer/sql 下的建表脚本");
    }

    /**
     * 极简 SQL 脚本解析：按分号切分，跳过注释行。
     * 这些脚本只包含 CREATE TABLE 与注释，不需要完整 SQL 解析器。
     */
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
