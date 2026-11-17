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
                                 `key_value` varchar(2000) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
                                 PRIMARY KEY (`rsa_key`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 1 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic;

