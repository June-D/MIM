package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.BaseNotify;
import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.ClearResult;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.service.PushService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 密文消息服务。
 *
 * <p>服务端对消息内容保持「零知识」：不解密、不校验语义，只做三件事 ——
 * 校验结构合法性、持久化、转发。因此这里的校验都是<b>结构性</b>的。
 *
 * <p>多设备：一条逻辑消息会以多份密文落库，每份对应一个目标设备。
 * 服务端只校验「目标设备确实存在且属于收信人」，不关心密文内容。
 */
@Slf4j
@Service
public class MessageServiceImpl implements MessageService {

    public static final String ALGORITHM_RATCHET = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM";

    /**
     * 兼容旧版本静态 ECDH。灰度期允许，后续应移除。
     */
    public static final String ALGORITHM_LEGACY = "ECDH-P256-HKDF-SHA256-AES-256-GCM";

    private static final Pattern CLIENT_MSG_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final int GCM_TAG_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;

    /**
     * 单次发送允许的最大设备副本数。与设备数上限一致，留一点余量。
     */
    private static final int MAX_FANOUT = 20;

    private final MessageMapper messageMapper;
    private final ConversationClearMapper conversationClearMapper;
    private final UserMapper userMapper;
    private final UserKeyMapper userKeyMapper;
    private final DeviceMapper deviceMapper;
    private final PushService pushService;

    @Value("${message.max-bytes:262144}")
    private int maxCiphertextBytes;

    @Value("${message.recall-window-seconds:120}")
    private long recallWindowSeconds;

    public MessageServiceImpl(MessageMapper messageMapper,
        ConversationClearMapper conversationClearMapper, UserMapper userMapper,
        UserKeyMapper userKeyMapper, DeviceMapper deviceMapper, PushService pushService) {
        this.messageMapper = messageMapper;
        this.conversationClearMapper = conversationClearMapper;
        this.userMapper = userMapper;
        this.userKeyMapper = userKeyMapper;
        this.deviceMapper = deviceMapper;
        this.pushService = pushService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CipherMessage> send(String authenticatedUser, String fromDeviceId,
        List<CipherMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new UserInvalidException("消息不能为空");
        }
        if (messages.size() > MAX_FANOUT) {
            throw new UserInvalidException("单次发送的设备副本数量过多");
        }

        String fromDevice = StringUtils.hasText(fromDeviceId)
            ? fromDeviceId.trim() : UserKey.DEFAULT_DEVICE_ID;

        // 所有副本必须属于同一条逻辑消息、同一个收信人
        String clientMsgId = messages.get(0).getClientMsgId();
        String toUsername = messages.get(0).getToUsername();
        for (CipherMessage message : messages) {
            if (message == null) {
                throw new UserInvalidException("消息不能为空");
            }
            if (!java.util.Objects.equals(clientMsgId, message.getClientMsgId())) {
                throw new UserInvalidException("同一次发送的消息 ID 必须一致");
            }
            if (!java.util.Objects.equals(toUsername, message.getToUsername())) {
                throw new UserInvalidException("同一次发送的收信人必须一致");
            }
        }

        validateEnvelope(authenticatedUser, toUsername, clientMsgId, messages.get(0));

        // 目标设备必须真实属于收信人且未注销
        Set<String> activeDevices = new HashSet<>();
        List<Device> devices = deviceMapper.selectActiveDevices(toUsername);
        if (devices != null) {
            for (Device device : devices) {
                activeDevices.add(device.getDeviceId());
            }
        }
        Set<String> seenTargets = new HashSet<>();
        for (CipherMessage message : messages) {
            String target = StringUtils.hasText(message.getTargetDeviceId())
                ? message.getTargetDeviceId().trim() : UserKey.DEFAULT_DEVICE_ID;
            if (!activeDevices.contains(target)) {
                throw new UserInvalidException("目标设备不存在或已注销：" + target);
            }
            if (!seenTargets.add(target)) {
                throw new UserInvalidException("同一目标设备出现多份密文：" + target);
            }
            // 每份密文单独校验 IV 与长度，避免畸形副本漏网
            validateCipherFields(message);
        }

        List<CipherMessage> saved = new ArrayList<>(messages.size());
        long now = System.currentTimeMillis();
        for (CipherMessage message : messages) {
            String target = StringUtils.hasText(message.getTargetDeviceId())
                ? message.getTargetDeviceId().trim() : UserKey.DEFAULT_DEVICE_ID;

            // 幂等：同一目标设备重复发送时返回首次落库的记录
            CipherMessage existing = messageMapper.selectByClientMsgId(authenticatedUser,
                clientMsgId, target);
            if (existing != null) {
                saved.add(existing);
                continue;
            }

            CipherMessage record = new CipherMessage();
            record.setClientMsgId(clientMsgId);
            record.setFromUsername(authenticatedUser);
            record.setFromDeviceId(fromDevice);
            record.setToUsername(toUsername);
            record.setTargetDeviceId(target);
            record.setAlg(message.getAlg());
            record.setIv(message.getIv());
            record.setCiphertext(message.getCiphertext());
            record.setRatchetPublic(message.getRatchetPublic());
            record.setPreviousChainLength(message.getPreviousChainLength());
            record.setMessageNumber(message.getMessageNumber());
            record.setSenderIdentityKey(message.getSenderIdentityKey());
            record.setSenderEphemeralKey(message.getSenderEphemeralKey());
            record.setSenderUsedOneTimePreKey(message.getSenderUsedOneTimePreKey());
            record.setConsumedOneTimePreKeyId(message.getConsumedOneTimePreKeyId());
            record.setSenderKeyVersion(message.getSenderKeyVersion());
            record.setRecipientKeyVersion(message.getRecipientKeyVersion());
            record.setClientTime(message.getClientTime());
            record.setServerTime(now);
            record.setDelivered(false);
            record.setReadFlag(false);
            record.setRecalled(false);

            try {
                messageMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发重试时以先落库的为准
                CipherMessage duplicate = messageMapper.selectByClientMsgId(authenticatedUser,
                    clientMsgId, target);
                if (duplicate != null) {
                    saved.add(duplicate);
                    continue;
                }
                throw e;
            }
            saved.add(record);
        }

        pushMessages(authenticatedUser, fromDevice, saved);
        return saved;
    }

    /**
     * 结构校验。所有副本共享同一套校验，取第一份即可。
     */
    private void validateEnvelope(String authenticatedUser, String toUsername, String clientMsgId,
        CipherMessage sample) {
        if (!StringUtils.hasText(toUsername)) {
            throw new UserInvalidException("收件人不能为空");
        }
        if (authenticatedUser.equals(toUsername)) {
            throw new UserInvalidException("不能给自己发消息");
        }
        ValidationUtil.validateUsername(toUsername);
        if (userMapper.selectOne(toUsername) == null) {
            throw new UserInvalidException("收件人不存在");
        }
        if (!isSupportedAlgorithm(sample.getAlg())) {
            throw new UserInvalidException("不支持的加密算法");
        }
        if (!StringUtils.hasText(clientMsgId) || !CLIENT_MSG_ID.matcher(clientMsgId).matches()) {
            throw new UserInvalidException("消息 ID 不合法");
        }
        if (sample.getClientTime() == null || sample.getClientTime() <= 0) {
            throw new UserInvalidException("消息时间戳不合法");
        }
        if (ALGORITHM_RATCHET.equals(sample.getAlg())) {
            validateRatchetHeader(sample);
        } else {
            validateLegacyKeyVersions(authenticatedUser, toUsername, sample);
        }
    }

    /**
     * 双棘轮消息必须带完整头字段，否则接收方无法推进棘轮。
     * 密文本身每个副本都要校验（长度与 Base64 合法性），因此单独遍历。
     */
    private void validateRatchetHeader(CipherMessage sample) {
        if (!StringUtils.hasText(sample.getRatchetPublic())) {
            throw new UserInvalidException("缺少棘轮公钥");
        }
        if (sample.getRatchetPublic().length() > 200) {
            throw new UserInvalidException("棘轮公钥过长");
        }
        if (sample.getPreviousChainLength() == null || sample.getPreviousChainLength() < 0) {
            throw new UserInvalidException("上次发送链长度不合法");
        }
        if (sample.getMessageNumber() == null || sample.getMessageNumber() < 0) {
            throw new UserInvalidException("消息序号不合法");
        }
    }

    private void validateLegacyKeyVersions(String authenticatedUser, String toUsername,
        CipherMessage message) {
        if (message.getSenderKeyVersion() == null || message.getSenderKeyVersion() < 1
            || message.getRecipientKeyVersion() == null || message.getRecipientKeyVersion() < 1) {
            throw new UserInvalidException("密钥版本不合法");
        }
        UserKey senderKey = userKeyMapper.selectByUsername(authenticatedUser);
        UserKey recipientKey = userKeyMapper.selectByUsername(toUsername);
        if (senderKey == null || recipientKey == null) {
            throw new UserInvalidException("用户尚未建立加密身份");
        }
        if (!senderKey.getVersion().equals(message.getSenderKeyVersion())) {
            throw new UserInvalidException("发信人公钥版本已过期，请刷新后重发");
        }
        if (!recipientKey.getVersion().equals(message.getRecipientKeyVersion())) {
            throw new UserInvalidException("收件人公钥版本已过期，请刷新后重发");
        }
    }

    /**
     * 每个副本单独校验 IV 与密文，避免某一份畸形密文漏网。
     */
    private void validateCipherFields(CipherMessage message) {
        byte[] iv = decode(message.getIv(), "IV");
        if (iv.length != GCM_IV_BYTES) {
            throw new UserInvalidException("IV 必须为 12 字节");
        }
        byte[] ciphertext = decode(message.getCiphertext(), "密文");
        if (ciphertext.length < GCM_TAG_BYTES + 1 || ciphertext.length > maxCiphertextBytes) {
            throw new UserInvalidException("密文长度不合法");
        }
    }

    private boolean isSupportedAlgorithm(String alg) {
        return ALGORITHM_RATCHET.equals(alg) || ALGORITHM_LEGACY.equals(alg);
    }

    /**
     * 推送新消息。
     *
     * <p>两份密文各投其主：
     * <ul>
     *   <li>推给收信人的<b>目标设备</b>——只有它能解密这份密文；</li>
     *   <li>推给发信人的<b>其它设备</b>（排除发信设备自己）做多端同步，
     *       否则自己刚发的消息在自己另一台设备上看不到。</li>
     * </ul>
     */
    private void pushMessages(String fromUser, String fromDevice, List<CipherMessage> records) {
        for (CipherMessage record : records) {
            try {
                BaseNotify<CipherMessage> notify =
                    BaseNotify.of(CommonConstants.NOTIFY_TYPE_NEW_MESSAGE, record);
                // 收信人侧：只推给这条密文的目标设备
                pushService.pushToDevice(record.getToUsername(), record.getTargetDeviceId(), notify);
                // 发信人侧：多端同步，排除发起设备（它已经有本地回显）
                pushService.pushToUserExcept(fromUser, fromDevice, notify);
            } catch (Exception e) {
                // 推送失败不影响已落库的事实，客户端通过 /undelivered 补齐
                log.warn("推送消息 id={} 失败：{}", record.getId(), e.getMessage());
            }
        }
    }

    @Override
    public List<CipherMessage> history(String username, String peer, Long beforeId, int limit,
        String deviceId) {
        ValidationUtil.validateUsername(peer);
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        List<CipherMessage> messages = StringUtils.hasText(deviceId)
            ? messageMapper.selectHistoryForDevice(username, peer, deviceId.trim(), beforeId, safeLimit)
            : messageMapper.selectHistory(username, peer, beforeId, safeLimit);
        long cursor = clearCursor(username, peer);
        if (cursor > 0 && messages != null) {
            messages.removeIf(message -> message.getId() != null && message.getId() <= cursor);
        }
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<CipherMessage> undelivered(String username, String deviceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        String device = StringUtils.hasText(deviceId)
            ? deviceId.trim() : UserKey.DEFAULT_DEVICE_ID;
        List<CipherMessage> messages = messageMapper.selectUndelivered(username, device, safeLimit);
        return messages == null ? Collections.emptyList() : messages;
    }

    @Override
    public void markDelivered(Long id) {
        if (id != null) {
            messageMapper.markDelivered(id);
        }
    }

    @Override
    public int markDeliveredTo(String username, String deviceId, long beforeId) {
        String device = StringUtils.hasText(deviceId)
            ? deviceId.trim() : UserKey.DEFAULT_DEVICE_ID;
        return messageMapper.markDeliveredUpTo(username, device, beforeId);
    }

    @Override
    public int markRead(String username, String peer, Long beforeId) {
        ValidationUtil.validateUsername(peer);
        int affected = messageMapper.markRead(peer, username, beforeId);
        if (affected > 0) {
            Map<String, Object> receipt = new LinkedHashMap<>(2);
            receipt.put("peer", username);
            receipt.put("beforeId", beforeId);
            try {
                pushService.pushToUser(peer, BaseNotify.of(CommonConstants.NOTIFY_TYPE_READ_RECEIPT, receipt));
            } catch (Exception e) {
                log.warn("已读回执推送失败：{}", e.getMessage());
            }
        }
        return affected;
    }

    @Override
    public long countUnread(String username, String peer) {
        ValidationUtil.validateUsername(peer);
        long cursor = clearCursor(username, peer);
        if (cursor > 0) {
            return messageMapper.countUnreadAfter(peer, username, cursor);
        }
        return messageMapper.countUnread(peer, username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearResult clearConversation(String username, String peer, boolean forBoth) {
        ValidationUtil.validateUsername(peer);
        if (username.equals(peer)) {
            throw new UserInvalidException("不能清除与自己的会话");
        }
        Long maxId = messageMapper.maxConversationId(username, peer);
        long cursor = maxId == null ? 0L : maxId;
        long now = System.currentTimeMillis();
        advanceCursor(username, peer, cursor, now);
        if (forBoth) {
            advanceCursor(peer, username, cursor, now);
            if (cursor > 0) {
                messageMapper.wipeConversation(username, peer, cursor);
            }
        }
        notifyCleared(username, peer, cursor, forBoth);
        if (forBoth) {
            notifyCleared(peer, username, cursor, true);
        }
        log.info("用户 {} 清除与 {} 的会话 forBoth={} beforeId={}", username, peer, forBoth, cursor);
        return new ClearResult(peer, cursor, forBoth);
    }

    private long clearCursor(String username, String peer) {
        Long cursor = conversationClearMapper.selectCursor(username, peer);
        return cursor == null ? 0L : cursor;
    }

    /**
     * 清除点只增不减。两个实例同时写时，后插入的一方碰到唯一键后再推进一次。
     */
    private void advanceCursor(String username, String peer, long cursor, long now) {
        Long existing = conversationClearMapper.selectCursor(username, peer);
        if (existing == null) {
            try {
                conversationClearMapper.insert(username, peer, cursor, now);
                return;
            } catch (DuplicateKeyException e) {
                log.info("清除点并发插入，改为推进 username={} peer={}", username, peer);
            }
        }
        conversationClearMapper.advance(username, peer, cursor, now);
    }

    private void notifyCleared(String target, String peer, long cursor, boolean forBoth) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(4);
            payload.put("peer", peer);
            payload.put("clearedBeforeId", cursor);
            payload.put("forBoth", forBoth);
            pushService.pushToUser(target,
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_CONVERSATION_CLEARED, payload));
        } catch (Exception e) {
            log.warn("清除通知推送失败：{}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CipherMessage recall(String username, String clientMsgId) {
        if (!StringUtils.hasText(clientMsgId)) {
            throw new UserInvalidException("消息 ID 不能为空");
        }
        // 撤回按 clientMsgId 作用于所有设备副本，因此找到任意一份副本即可判定权限与时限
        CipherMessage sample = findAnyCopy(username, clientMsgId);
        if (sample == null) {
            throw new UserInvalidException("消息不存在");
        }
        if (!username.equals(sample.getFromUsername())) {
            throw new UserInvalidException("只能撤回自己发送的消息");
        }
        if (Boolean.TRUE.equals(sample.getRecalled())) {
            throw new UserInvalidException("消息已撤回");
        }
        if (System.currentTimeMillis() - sample.getServerTime() > recallWindowSeconds * 1000L) {
            throw new UserInvalidException("超过撤回时限（" + recallWindowSeconds + " 秒）");
        }

        long recalledAt = System.currentTimeMillis();
        int affected = messageMapper.recall(clientMsgId, username, recalledAt);
        if (affected == 0) {
            // 并发撤回：另一请求已处理
            throw new UserInvalidException("消息已撤回");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>(4);
            payload.put("clientMsgId", clientMsgId);
            payload.put("fromUsername", sample.getFromUsername());
            payload.put("toUsername", sample.getToUsername());
            payload.put("recalledAt", recalledAt);
            BaseNotify<Map<String, Object>> notify =
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_MESSAGE_RECALLED, payload);
            // 撤回事件要通知双方<b>所有设备</b>，把各端渲染的消息都替换成占位
            pushService.pushToUser(sample.getToUsername(), notify);
            pushService.pushToUser(sample.getFromUsername(), notify);
        } catch (Exception e) {
            log.warn("撤回通知推送失败：{}", e.getMessage());
        }

        sample.setRecalled(true);
        sample.setRecalledAt(recalledAt);
        sample.setCiphertext(null);
        sample.setIv(null);
        log.info("用户 {} 撤回消息 {}", username, clientMsgId);
        return sample;
    }

    private static final int MAX_SYNC_BATCH = 50;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CipherMessage> syncReplicas(String username, String fromDeviceId,
        List<CipherMessage> replicas) {
        if (replicas == null || replicas.isEmpty()) {
            throw new UserInvalidException("没有可同步的消息");
        }
        if (replicas.size() > MAX_SYNC_BATCH) {
            throw new UserInvalidException("单次同步的消息过多");
        }
        String fromDevice = requireOwnDevice(username, fromDeviceId);
        List<CipherMessage> saved = new ArrayList<>();
        for (CipherMessage replica : replicas) {
            saved.add(syncOne(username, fromDevice, replica));
        }
        return saved;
    }

    @Override
    public void requestHistorySync(String username, String deviceId) {
        String device = requireOwnDevice(username, deviceId);
        Map<String, Object> payload = new LinkedHashMap<>(1);
        payload.put("deviceId", device);
        try {
            pushService.pushToUserExcept(username, device,
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_HISTORY_SYNC, payload));
        } catch (Exception e) {
            log.warn("历史同步请求推送失败：{}", e.getMessage());
        }
    }

    /**
     * 按库里的原消息复制一行，密文换成请求里这份。请求体里的发信人会被忽略。
     */
    private CipherMessage syncOne(String username, String fromDevice, CipherMessage replica) {
        if (replica == null || !StringUtils.hasText(replica.getClientMsgId())
            || !CLIENT_MSG_ID.matcher(replica.getClientMsgId()).matches()) {
            throw new UserInvalidException("消息 ID 不合法");
        }
        String target = requireOwnDevice(username, replica.getTargetDeviceId());
        if (target.equals(fromDevice)) {
            throw new UserInvalidException("不能把历史同步给当前设备");
        }
        CipherMessage original = messageMapper.selectParticipantCopy(username, replica.getClientMsgId());
        if (original == null) {
            throw new UserInvalidException("没有可同步的原消息");
        }
        CipherMessage existing = messageMapper.selectByClientMsgId(original.getFromUsername(),
            original.getClientMsgId(), target);
        if (existing != null) {
            return existing;
        }
        String other = username.equals(original.getFromUsername())
            ? original.getToUsername() : original.getFromUsername();
        if (original.getId() != null && original.getId() <= clearCursor(username, other)) {
            CipherMessage skipped = new CipherMessage();
            skipped.setClientMsgId(original.getClientMsgId());
            return skipped;
        }

        CipherMessage record = new CipherMessage();
        record.setClientMsgId(original.getClientMsgId());
        record.setFromUsername(original.getFromUsername());
        record.setFromDeviceId(original.getFromDeviceId());
        record.setToUsername(original.getToUsername());
        record.setTargetDeviceId(target);
        record.setSyncFromDevice(fromDevice);
        record.setClientTime(original.getClientTime());
        record.setReadFlag(Boolean.TRUE.equals(original.getReadFlag()));
        record.setDelivered(false);
        record.setServerTime(System.currentTimeMillis());
        if (Boolean.TRUE.equals(original.getRecalled())) {
            record.setAlg(original.getAlg() == null ? ALGORITHM_RATCHET : original.getAlg());
            record.setRecalled(true);
        } else {
            if (!ALGORITHM_RATCHET.equals(replica.getAlg())) {
                throw new UserInvalidException("同步消息的加密算法不支持");
            }
            validateRatchetHeader(replica);
            validateCipherFields(replica);
            record.setAlg(replica.getAlg());
            record.setIv(replica.getIv());
            record.setCiphertext(replica.getCiphertext());
            record.setRatchetPublic(replica.getRatchetPublic());
            record.setPreviousChainLength(replica.getPreviousChainLength());
            record.setMessageNumber(replica.getMessageNumber());
            record.setSenderIdentityKey(replica.getSenderIdentityKey());
            record.setSenderEphemeralKey(replica.getSenderEphemeralKey());
            record.setSenderUsedOneTimePreKey(replica.getSenderUsedOneTimePreKey());
            record.setConsumedOneTimePreKeyId(replica.getConsumedOneTimePreKeyId());
            record.setRecalled(false);
        }
        try {
            messageMapper.insert(record);
        } catch (DuplicateKeyException e) {
            CipherMessage duplicate = messageMapper.selectByClientMsgId(original.getFromUsername(),
                original.getClientMsgId(), target);
            if (duplicate != null) {
                return duplicate;
            }
            throw e;
        }
        try {
            pushService.pushToDevice(username, target,
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_NEW_MESSAGE, record));
        } catch (Exception e) {
            log.warn("历史同步推送失败：{}", e.getMessage());
        }
        return record;
    }

    private String requireOwnDevice(String username, String deviceId) {
        if (!StringUtils.hasText(deviceId) || !deviceId.trim().matches("^[A-Za-z0-9._-]{1,64}$")) {
            throw new UserInvalidException("设备标识不合法");
        }
        String device = deviceId.trim();
        Device registered = deviceMapper.selectOne(username, device);
        if (registered == null || Boolean.TRUE.equals(registered.getRevoked())) {
            throw new UserInvalidException("设备未注册或已注销");
        }
        return device;
    }

    /**
     * 找到该 clientMsgId 的任意一份副本（不限设备）。
     */
    private CipherMessage findAnyCopy(String fromUser, String clientMsgId) {
        return messageMapper.selectAnyByClientMsgId(fromUser, clientMsgId);
    }

    private byte[] decode(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new UserInvalidException(field + "不能为空");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException(field + "不是合法的 Base64");
        }
    }
}
