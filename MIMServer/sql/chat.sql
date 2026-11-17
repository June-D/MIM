SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `t_register_users`;
CREATE TABLE `t_register_users`  (
                              `id` int NOT NULL AUTO_INCREMENT comment '自增主键',
                              `username` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '用户名',
                              `password` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '密码',
                              `nickname` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '昵称',
                              `pic` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '头像',
                              `signature` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '签名',
                              `status` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '状态：',
                              `crt_time` datetime NULL DEFAULT NULL comment '创建时间',
                              `upd_time` datetime NULL DEFAULT NULL comment '更新时间',
                              `last_login_time` datetime NULL DEFAULT NULL comment '最后登录时间',
                              `totp_secret` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment 'TOTP 密钥（AES-GCM 密文，兼容旧 Base32）',
                              `totp_enabled` tinyint(1) NOT NULL DEFAULT 0 comment '是否启用 TOTP 二次验证',
                              `email_cipher` varchar(512) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '加密后的邮箱（仅本人可见）',
                              `terms_version` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '同意的用户协议版本',
                              `agreed_at` datetime NULL DEFAULT NULL comment '同意用户协议时间',
                              PRIMARY KEY (`id`) USING BTREE,
                              UNIQUE INDEX `idx_username`(`username`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 32 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic comment '注册用户';


DROP TABLE IF EXISTS `t_chat_group`;
CREATE TABLE `t_chat_group`  (
                                        `id` int NOT NULL AUTO_INCREMENT comment '自增主键',
                                        `owner_user` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '拥有人',
                                        `group_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '组ID',
                                        `group_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '组名称',
                                        `crt_time` datetime NULL DEFAULT NULL comment '创建时间',
                                        PRIMARY KEY (`id`) USING BTREE,
                                        UNIQUE INDEX `id_13178295930045`(`id`) USING BTREE,
                                        INDEX `idx_owner_user`(`owner_user`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 32 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic comment '组';

DROP TABLE IF EXISTS `t_chat_users`;
CREATE TABLE `t_chat_users`  (
                               `id` int NOT NULL AUTO_INCREMENT comment '自增主键',
                               `owner_user` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '主人',
                               `username` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '客人',
                               `label_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '客人标签',
                               `group_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL comment '分组',
                               `crt_time` timestamp NULL DEFAULT NULL comment '创建时间',
                               PRIMARY KEY (`id`) USING BTREE,
                               UNIQUE INDEX `id_13178295930045`(`id`) USING BTREE,
                               INDEX `idx_owner_user`(`owner_user`) USING BTREE,
                               INDEX `idx_username`(`username`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 32 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic comment '对话用户';


-- ----------------------------
-- Table structure for base_rsa_key
-- ----------------------------
DROP TABLE IF EXISTS `t_rsa_key`;
CREATE TABLE `t_rsa_key`  (
                                 `rsa_key` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
                                 `key_value` varchar(4096) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
                                 PRIMARY KEY (`rsa_key`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 1 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic;


-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：
-- TOTP 二次验证。密钥为 AES-GCM 密文（兼容旧 Base32）；enabled 为 0 表示尚未启用。
-- ------------------------------------------------------------
-- ALTER TABLE `t_register_users`
--     ADD COLUMN `totp_secret` varchar(255) NULL COMMENT 'TOTP 密钥（AES-GCM 密文，兼容旧 Base32）',
--     ADD COLUMN `totp_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用 TOTP 二次验证';
--
-- 已存在 totp_secret 的旧库需要扩容（加密后的密钥约 81 字符）：
-- ALTER TABLE `t_register_users`
--     MODIFY COLUMN `totp_secret` varchar(255) NULL COMMENT 'TOTP 密钥（AES-GCM 密文，兼容旧 Base32）';

-- 启用 MIM_MASTER_KEY 前先扩容 JWT 密钥列（加密后的 RSA 私钥约 2.2KB）：
-- ALTER TABLE `t_rsa_key`
--     MODIFY COLUMN `key_value` varchar(4096) NOT NULL;

-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：
-- 邮箱找回密码与用户协议。
-- ------------------------------------------------------------
-- ALTER TABLE `t_register_users`
--     ADD COLUMN `email_cipher` varchar(512) NULL COMMENT '加密后的邮箱（仅本人可见）',
--     ADD COLUMN `terms_version` varchar(16) NULL COMMENT '同意的用户协议版本',
--     ADD COLUMN `agreed_at` datetime NULL COMMENT '同意用户协议时间';
