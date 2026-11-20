-- ============================================================
-- MIM 端到端加密表结构（v3：多设备）
--
-- 服务器只保存公钥与密文，永远不持有任何私钥或会话密钥。
--
-- 多设备的核心变化：
--   每个设备拥有自己独立的身份密钥、预密钥与棘轮状态。
--   给一个用户发消息，需要为他的每个设备各加密一份密文。
--   因此 t_user_key / t_signed_prekey / t_one_time_prekey 的主键
--   从 username 扩展为 (username, device_id)。
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 设备注册表
--
-- 一个用户可以有多个设备。device_id 由客户端生成（随机串），
-- 服务端只做登记，不参与密钥推导。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_device` (
    `username`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
    `device_id`     varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端生成的设备标识',
    `device_name`   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '设备名，仅用于用户辨认',
    `crt_time`      bigint       NOT NULL COMMENT '注册时间（毫秒）',
    `last_seen`     bigint       NOT NULL COMMENT '最近活跃时间（毫秒）',
    `revoked`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已注销（注销后不再接收消息）',
    `token_version` int          NOT NULL DEFAULT 0 COMMENT '令牌版本，登出/注销时自增使旧 JWT 失效',
    PRIMARY KEY (`username`, `device_id`) USING BTREE,
    INDEX `idx_username` (`username`, `revoked`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '用户设备';

-- ------------------------------------------------------------
-- 设备身份公钥
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user_key` (
    `username`    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
    `device_id`   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'default' COMMENT '设备标识',
    `public_key`  text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Base64(X.509 SubjectPublicKeyInfo)，EC P-256',
    `algorithm`   varchar(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'EC-P256' COMMENT '密钥算法标识',
    `key_version` int          NOT NULL DEFAULT 1 COMMENT '密钥版本，每次换钥自增',
    `fingerprint` varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公钥 SHA-256 指纹，供线下核验',
    `supports_padding` tinyint(1) NOT NULL DEFAULT 0 COMMENT '客户端是否支持消息长度填充',
    `supports_recall_token` tinyint(1) NOT NULL DEFAULT 0 COMMENT '客户端能否接收加密的撤回凭证（自己设备间同步）',
    `supports_sender_cert` tinyint(1) NOT NULL DEFAULT 0 COMMENT '客户端能否解析首条消息里的发送者证书',
    `crt_time`    datetime     NULL DEFAULT NULL COMMENT '创建时间',
    `upd_time`    datetime     NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`username`, `device_id`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '设备身份公钥目录';

-- ------------------------------------------------------------
-- 签名预密钥（按设备）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_signed_prekey` (
    `username`    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
    `device_id`   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'default' COMMENT '设备标识',
    `pre_key_id`  int          NOT NULL COMMENT '预密钥编号，客户端轮换时自增',
    `public_key`  text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Base64(X.509) EC P-256 公钥',
    `signature`   text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Base64(ECDSA-SHA256)，签名内容为 preKeyId||publicKey',
    `crt_time`    bigint       NOT NULL COMMENT '创建时间（毫秒）',
    `upd_time`    bigint       NOT NULL COMMENT '更新时间（毫秒）',
    PRIMARY KEY (`username`, `device_id`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '设备签名预密钥';

-- ------------------------------------------------------------
-- 一次性预密钥（按设备）。先按请求者预留，消息真正使用后再删除；
-- 预留超时会被释放，避免领取后不发送导致密钥永久浪费。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_one_time_prekey` (
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `username`    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
    `device_id`   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'default' COMMENT '设备标识',
    `pre_key_id`  int          NOT NULL COMMENT '预密钥编号',
    `public_key`  text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Base64(X.509) EC P-256 公钥',
    `crt_time`    bigint       NOT NULL COMMENT '创建时间（毫秒）',
    `claimed_by`  varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '预留该 OPK 的请求者（username@deviceId）',
    `claimed_at`  bigint       NULL DEFAULT NULL COMMENT '预留时间（毫秒）',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_device_prekey` (`username`, `device_id`, `pre_key_id`) USING BTREE,
    INDEX `idx_device` (`username`, `device_id`, `id`) USING BTREE,
    INDEX `idx_claim` (`username`, `device_id`, `claimed_by`, `id`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '设备一次性预密钥';

-- ------------------------------------------------------------
-- 密文消息（v3：带源/目标设备）
--
-- target_device_id 指明这条密文是给哪个设备解密用的。
-- 给 N 个设备发同一条消息，会落 N 行，它们的 client_msg_id 相同、
-- target_device_id 不同。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_message` (
    `id`                    bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `client_msg_id`         varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端生成的唯一消息 ID（同一逻辑消息的多个设备副本共用）',
    `to_user`               varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收信人',
    `target_device_id`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'default' COMMENT '这条密文的目标设备',
    `alg`                   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '加密算法标识',
    `iv`                    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Base64(12 字节随机 IV)，撤回后清空',
    `ciphertext`            mediumtext   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'Base64(AES-GCM 密文，含 16 字节 tag)，撤回后清空',
    `ratchet_public`        varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Base64 棘轮公钥',
    `previous_chain_length` int          NULL DEFAULT NULL COMMENT '上一次发送链长度',
    `message_number`        int          NULL DEFAULT NULL COMMENT '当前发送链上的消息序号',
    `sender_identity_key`   text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'X3DH 握手：发信人身份公钥，仅首条消息携带',
    `sender_ephemeral_key`  varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'X3DH 握手：发信人临时公钥，仅首条消息携带',
    `sender_used_opk`       tinyint(1)   NULL DEFAULT NULL COMMENT 'X3DH 握手：是否使用了一次性预密钥',
    `consumed_opk_id`       int          NULL DEFAULT NULL COMMENT 'X3DH 握手：被消耗的一次性预密钥编号',
    `recipient_spk_id`      int          NULL DEFAULT NULL COMMENT 'X3DH 握手：使用接收方哪一把签名预密钥',
    `padding`               tinyint(1)   NOT NULL DEFAULT 0 COMMENT '明文是否做了长度填充',
    `sealed`                tinyint(1)   NOT NULL DEFAULT 0 COMMENT '已建立会话后是否隐藏了明文发信人/设备（使用盲引用）',
    `session_id`            varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'sealed 会话 ID，收件端据此索引棘轮状态',
    `sender_key_version`    int          NULL DEFAULT NULL COMMENT '发信人身份公钥版本（兼容旧算法）',
    `recipient_key_version` int          NULL DEFAULT NULL COMMENT '收信人身份公钥版本（兼容旧算法）',
    `client_time`           bigint       NOT NULL COMMENT '客户端时间戳（毫秒），参与 AAD',
    `server_time`           bigint       NOT NULL COMMENT '服务器接收时间（毫秒）',
    `delivered`             tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已投递到接收端',
    `read_flag`             tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已读',
    `recalled`              tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已撤回',
    `recalled_at`           bigint       NULL DEFAULT NULL COMMENT '撤回时间（毫秒）',
    `recall_token_hash`     varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '撤回凭证的 SHA-256；有值时撤回必须携带原始 token',
    `conversation_tag`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '客户端生成的会话标签（不透明），替代可从用户名推导的 HMAC 盲引用',
    `sync_from_device`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '哪台设备把原文重新加密给目标设备；空表示原始副本',
    `sync_original_from`    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '同步副本：原消息发送者（由转发设备签名）',
    `sync_original_to`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '同步副本：原消息收信人（由转发设备签名）',
    `sync_original_device`  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '同步副本：原消息发送设备（由转发设备签名）',
    `sync_signature`        text         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '同步副本：转发设备对原消息归属与内容的签名',
    PRIMARY KEY (`id`) USING BTREE,
    -- 同一条逻辑消息对同一目标设备只会有一份；不带发送者，
    -- 避免把发信人重新引入唯一索引
    UNIQUE INDEX `uk_client_target` (`client_msg_id`, `target_device_id`) USING BTREE,
    INDEX `idx_inbox` (`to_user`, `target_device_id`, `delivered`, `id`) USING BTREE,
    INDEX `idx_unread` (`to_user`, `target_device_id`, `read_flag`) USING BTREE,
    INDEX `idx_tag_conversation` (`conversation_tag`, `id`) USING BTREE,
    INDEX `idx_tag_inbox` (`conversation_tag`, `to_user`, `id`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '端到端加密密文消息';

-- ------------------------------------------------------------
-- 好友申请
--
-- 加好友必须对方同意。申请先落在这张表，同意后才写入 t_chat_users。
-- 状态：0 待同意，1 已同意，2 已拒绝。拒绝后可以再次申请，复用同一行。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_friend_request` (
    `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '申请编号',
    `from_user`  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请人',
    `to_user`    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '被申请人',
    `status`     tinyint      NOT NULL DEFAULT 0 COMMENT '0待同意 1已同意 2已拒绝',
    `created_at` bigint       NOT NULL COMMENT '创建时间（毫秒）',
    `updated_at` bigint       NOT NULL COMMENT '最近状态变更（毫秒）',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_pair` (`from_user`, `to_user`) USING BTREE,
    INDEX `idx_inbox` (`to_user`, `status`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '好友申请，同意后才成为联系人';

-- ------------------------------------------------------------
-- 会话清除点
--
-- 每个用户对每个对端一条。cleared_before_id 及更早的消息对该用户不可见。
-- 双方清除时写两条，并抹掉对应密文。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_conversation_clear` (
    `username`           varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '执行清除的用户',
    `peer`               varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '对话对象',
    `cleared_before_id`  bigint      NOT NULL COMMENT '这个 id 及更早的消息不再展示',
    `updated_at`         bigint      NOT NULL COMMENT '最近推进时间（毫秒）',
    PRIMARY KEY (`username`, `peer`) USING BTREE
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = Dynamic COMMENT = '会话清除点，只增不减';

-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：
-- 设备令牌版本。登出/注销时自增，使该设备已签发的 JWT 立即失效。
-- ------------------------------------------------------------
-- ALTER TABLE `t_device`
--     ADD COLUMN `token_version` int NOT NULL DEFAULT 0 COMMENT '令牌版本' AFTER `revoked`;

-- ------------------------------------------------------------
-- 同步副本的来源签名，用于防止服务端把历史内容挂到错误的联系人名下。
-- ------------------------------------------------------------
-- ALTER TABLE `t_message`
--     ADD COLUMN `sync_original_from` varchar(64) NULL COMMENT '同步副本原发送者',
--     ADD COLUMN `sync_original_to` varchar(64) NULL COMMENT '同步副本原收信人',
--     ADD COLUMN `sync_original_device` varchar(64) NULL COMMENT '同步副本原发送设备',
--     ADD COLUMN `sync_signature` text NULL COMMENT '同步副本来源签名',
--     ADD COLUMN `recipient_spk_id` int NULL COMMENT 'X3DH 使用的接收方签名预密钥编号';

-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：
-- 早期启动 DDL 创建的这两张表可能继承 MySQL 8 默认 collation，
-- 与 t_message 的 utf8mb4_general_ci 比较时会报 1267。
-- ------------------------------------------------------------
-- ALTER TABLE `t_conversation_clear`
--     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
-- ALTER TABLE `t_friend_request`
--     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：消息长度填充与能力协商
-- ------------------------------------------------------------
-- ALTER TABLE `t_user_key`
--     ADD COLUMN `supports_padding` tinyint(1) NOT NULL DEFAULT 0
--         COMMENT '客户端是否支持消息长度填充';
-- ALTER TABLE `t_message`
--     ADD COLUMN `padding` tinyint(1) NOT NULL DEFAULT 0
--         COMMENT '明文是否做了长度填充';

-- ------------------------------------------------------------
-- 从旧版本升级（取消注释后执行）：Phase 2 协议断代
--
-- 本版本已彻底移除「明文发送者 + HMAC 盲引用」存储：
-- 新消息只写 conversation_tag，历史/未读/清除/撤回/同步全部按标签。
-- 旧消息（没有标签）无法迁移——标签是客户端随机生成、服务端无从推导。
--
-- 因此升级顺序固定，且**不可逆**：
--   1. 归档无标签旧消息；
--   2. 删除无标签旧消息；
--   3. 删除发送者列与三个旧索引；
--   4. 清理按用户名记录的清除点（只保留 tag: 前缀）。
-- 若还需要旧消息，必须先自行备份 t_message_legacy_archive。
-- ------------------------------------------------------------
-- CREATE TABLE IF NOT EXISTS `t_message_legacy_archive` LIKE `t_message`;
-- INSERT INTO `t_message_legacy_archive` SELECT * FROM `t_message`
--  WHERE `conversation_tag` IS NULL;
-- DELETE FROM `t_message` WHERE `conversation_tag` IS NULL;
-- ALTER TABLE `t_message`
--     DROP INDEX `idx_conversation`,
--     DROP INDEX `idx_ref_conversation`,
--     DROP INDEX `idx_ref_unread`,
--     DROP COLUMN `from_user`,
--     DROP COLUMN `from_device_id`,
--     DROP COLUMN `from_ref`,
--     DROP COLUMN `from_device_ref`;
-- DELETE FROM `t_conversation_clear` WHERE `peer` NOT LIKE 'tag:%';

-- 加密媒体（服务端只存密文文件 + 元数据；文件名/类型都在端到端密文里）
CREATE TABLE IF NOT EXISTS t_media (
  id varchar(64) NOT NULL,
  owner varchar(64) NOT NULL,
  size bigint NOT NULL,
  crt_time bigint NOT NULL,
  PRIMARY KEY (id),
  KEY idx_media_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
