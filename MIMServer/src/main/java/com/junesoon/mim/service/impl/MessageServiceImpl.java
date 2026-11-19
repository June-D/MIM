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
import com.junesoon.mim.mapper.PreKeyMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.ClearResult;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.service.PushService;
import com.junesoon.mim.service.SenderTokenService;
import com.junesoon.mim.utils.RecallToken;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 可选注入：OPK 使用后需要删除预留记录。单元测试直接构造本类时可以为空。
     */
    @Autowired(required = false)
    private PreKeyMapper preKeyMapper;


    @Value("${message.max-bytes:262144}")
    private int maxCiphertextBytes;

    @Value("${message.recall-window-seconds:120}")
    private long recallWindowSeconds;

    /**
     * 可选注入：匿名提交的令牌校验。单元测试直接构造时可空，
     * 匿名入口因此不可用。
     */
    @Autowired(required = false)
    private SenderTokenService senderTokenService;

    @Value("${mim.anonymous-send.enabled:false}")
    private boolean anonymousSendEnabled;

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
        // 发信设备必须是调用者自己已注册且未注销的设备。
        // 否则任何人都能凭空伪造 deviceId，让收信端为每个假设备建立一个新会话
        //（新 X3DH + 新会话文件 + 消耗 OPK），属于低成本的磁盘/CPU 放大攻击。
        if (!UserKey.DEFAULT_DEVICE_ID.equals(fromDevice)) {
            fromDevice = requireOwnDevice(authenticatedUser, fromDevice);
        }

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
            requireTaggedSealed(message);
        }

        validateEnvelope(authenticatedUser, toUsername, clientMsgId, messages.get(0));

        // 首条握手消息里的身份公钥必须与发信设备登记的公钥一致。
        // 接收方虽然也会做 TOFU 校验，但服务端先挡掉明显伪造的声明，
        // 避免把畸形包扩散给收信端。sealed 的首条消息同样需要校验：
        // 身份公钥是收信端建会话的唯一依据。
        if (!UserKey.DEFAULT_DEVICE_ID.equals(fromDevice)) {
            UserKey senderKey = userKeyMapper.selectByDevice(authenticatedUser, fromDevice);
            String claimed = messages.get(0).getSenderIdentityKey();
            if (senderKey != null && StringUtils.hasText(claimed)
                && !claimed.equals(senderKey.getPublicKey())) {
                throw new UserInvalidException("发信设备身份公钥与登记值不一致");
            }
        }

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
        // 撤回凭证按逻辑消息只生成一份；服务端只保存哈希，永远拿不到原始 token。
        String recallTokenHash = recallTokenHash(messages.get(0).getRecallToken());
        for (CipherMessage message : messages) {
            String target = StringUtils.hasText(message.getTargetDeviceId())
                ? message.getTargetDeviceId().trim() : UserKey.DEFAULT_DEVICE_ID;
            String tag = requireTaggedSealed(message);
            // 协议断代后不保存任何发送者形式：没有 from_user / from_device_id，
            // 也没有可从用户名推导的盲引用，只有客户端生成的会话标签。

            // 幂等：同一目标设备重复发送时返回首次落库的记录
            CipherMessage existing = messageMapper.selectByClientMsgId(clientMsgId, target);
            if (existing != null) {
                consumeOneTimePreKey(message, toUsername, target, authenticatedUser, fromDevice);
                saved.add(existing);
                continue;
            }

            CipherMessage record = new CipherMessage();
            record.setClientMsgId(clientMsgId);
            record.setRecallTokenHash(recallTokenHash);
            record.setConversationTag(tag);
            record.setSealed(true);
            record.setSessionId(message.getSessionId());
            record.setToUsername(toUsername);
            record.setTargetDeviceId(target);
            record.setAlg(message.getAlg());
            record.setIv(message.getIv());
            record.setCiphertext(message.getCiphertext());
            record.setRatchetPublic(message.getRatchetPublic());
            record.setPreviousChainLength(message.getPreviousChainLength());
            record.setMessageNumber(message.getMessageNumber());
            // 握手参数的生命周期：首条消息必须带，收信端才能离线建出会话；
            // 收信设备确认投递后由 markDeliveredUpTo 立刻清空。
            // 已建立会话的新客户端不再发送这些字段，所以这里按请求原样保存。
            record.setSenderIdentityKey(message.getSenderIdentityKey());
            record.setSenderEphemeralKey(message.getSenderEphemeralKey());
            record.setSenderUsedOneTimePreKey(message.getSenderUsedOneTimePreKey());
            record.setConsumedOneTimePreKeyId(message.getConsumedOneTimePreKeyId());
            record.setRecipientSignedPreKeyId(message.getRecipientSignedPreKeyId());
            record.setPadding(Boolean.TRUE.equals(message.getPadding()));
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
                CipherMessage duplicate = messageMapper.selectByClientMsgId(clientMsgId, target);
                if (duplicate != null) {
                    consumeOneTimePreKey(message, toUsername, target, authenticatedUser, fromDevice);
                    saved.add(duplicate);
                    continue;
                }
                throw e;
            }
            consumeOneTimePreKey(message, toUsername, target, authenticatedUser, fromDevice);
            saved.add(record);
        }

        pushMessages(saved);
        return saved;
    }

    /**
     * 撤回凭证只保存哈希。没有 token 的消息仍可发送，但之后无法撤回。
     */
    private String recallTokenHash(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        if (!RecallToken.isValid(token)) {
            throw new UserInvalidException("撤回凭证格式不合法");
        }
        return RecallToken.hash(token);
    }

    /**
     * Phase 2 断代后，消息必须是「sealed + 会话标签」：
     * 服务端不再保存任何发送者形式，标签是历史/未读/清除/撤回的唯一索引。
     */
    private String requireTaggedSealed(CipherMessage message) {
        if (!Boolean.TRUE.equals(message.getSealed())) {
            throw new UserInvalidException("消息必须使用 sealed 格式");
        }
        String tag = normalizeTag(message.getConversationTag());
        if (tag == null) {
            throw new UserInvalidException("消息必须携带合法的会话标签");
        }
        if (!StringUtils.hasText(message.getSessionId())
            || !message.getSessionId().matches("^[A-Za-z0-9-]{8,64}$")) {
            throw new UserInvalidException("sealed 会话 ID 不合法");
        }
        return tag;
    }

    private void validateEnvelope(String authenticatedUser, String toUsername, String clientMsgId,
        CipherMessage sample) {
        if (!StringUtils.hasText(toUsername)) {
            throw new UserInvalidException("收件人不能为空");
        }
        if (authenticatedUser != null && authenticatedUser.equals(toUsername)) {
            throw new UserInvalidException("不能给自己发消息");
        }
        ValidationUtil.validateUsername(toUsername);
        if (userMapper.selectOne(toUsername) == null) {
            throw new UserInvalidException("收件人不存在");
        }
        if (!ALGORITHM_RATCHET.equals(sample.getAlg())) {
            throw new UserInvalidException("不支持的加密算法");
        }
        if (!StringUtils.hasText(clientMsgId) || !CLIENT_MSG_ID.matcher(clientMsgId).matches()) {
            throw new UserInvalidException("消息 ID 不合法");
        }
        if (sample.getClientTime() == null || sample.getClientTime() <= 0) {
            throw new UserInvalidException("消息时间戳不合法");
        }
        // 客户端时间会直接展示在界面上，允许少量时钟偏差，但不允许任意倒填/穿越
        long now = System.currentTimeMillis();
        long maxPast = now - 365L * 24 * 60 * 60 * 1000;
        long maxFuture = now + 5L * 60 * 1000;
        if (sample.getClientTime() < maxPast || sample.getClientTime() > maxFuture) {
            throw new UserInvalidException("消息时间戳超出允许范围");
        }
        validateRatchetHeader(sample);
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
    /**
     * 只推收信人的目标设备。消息没有任何发送者形式，发给发送者其它设备
     * 的副本既解不开、也无法归属，多端历史统一走设备间同步。
     */
    private void pushMessages(List<CipherMessage> records) {
        for (CipherMessage record : records) {
            try {
                BaseNotify<CipherMessage> notify =
                    BaseNotify.of(CommonConstants.NOTIFY_TYPE_NEW_MESSAGE, record);
                pushService.pushToDevice(record.getToUsername(), record.getTargetDeviceId(), notify);
            } catch (Exception e) {
                // 推送失败不影响已落库的事实，客户端通过 /undelivered 补齐
                log.warn("推送消息 id={} 失败：{}", record.getId(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CipherMessage> sendAnonymous(String senderToken, List<CipherMessage> messages) {
        if (!anonymousSendEnabled || senderTokenService == null) {
            throw new UserInvalidException("匿名提交未启用");
        }
        // 先消费令牌：即使后续校验失败也不退，避免被用来探测校验逻辑。
        senderTokenService.consume(senderToken);

        if (messages == null || messages.isEmpty()) {
            throw new UserInvalidException("没有可提交的消息");
        }
        CipherMessage sample = messages.get(0);
        String clientMsgId = sample.getClientMsgId();
        String toUsername = sample.getToUsername();
        String tag = normalizeTag(sample.getConversationTag());
        if (tag == null) {
            throw new UserInvalidException("匿名提交必须携带会话标签");
        }
        if (!Boolean.TRUE.equals(sample.getSealed())) {
            throw new UserInvalidException("匿名提交只支持 sealed 消息");
        }
        if (!ALGORITHM_RATCHET.equals(sample.getAlg())) {
            throw new UserInvalidException("匿名提交只支持双棘轮消息");
        }
        // 匿名入口没有账号：传空字符串跳过「不能给自己发」的判定。
        validateEnvelope("", toUsername, clientMsgId, sample);
        if (!StringUtils.hasText(sample.getSessionId())
            || !sample.getSessionId().matches("^[A-Za-z0-9-]{8,64}$")) {
            throw new UserInvalidException("sealed 会话 ID 不合法");
        }

        Set<String> activeDevices = new HashSet<>();
        List<Device> devices = deviceMapper.selectActiveDevices(toUsername);
        if (devices != null) {
            for (Device device : devices) {
                activeDevices.add(device.getDeviceId());
            }
        }
        Set<String> seenTargets = new HashSet<>();
        for (CipherMessage message : messages) {
            String target = targetOf(message);
            if (!activeDevices.contains(target)) {
                throw new UserInvalidException("目标设备不存在或已注销：" + target);
            }
            if (!seenTargets.add(target)) {
                throw new UserInvalidException("同一目标设备出现多份密文：" + target);
            }
            boolean same = clientMsgId.equals(message.getClientMsgId())
                && toUsername.equals(message.getToUsername())
                && tag.equals(normalizeTag(message.getConversationTag()))
                && Boolean.TRUE.equals(message.getSealed())
                && ALGORITHM_RATCHET.equals(message.getAlg());
            if (!same) {
                throw new UserInvalidException("同一逻辑消息的所有副本必须归属一致");
            }
            validateCipherFields(message);
        }

        String recallTokenHash = recallTokenHash(sample.getRecallToken());
        long now = System.currentTimeMillis();
        List<CipherMessage> saved = new ArrayList<>(messages.size());
        for (CipherMessage message : messages) {
            String target = targetOf(message);
            CipherMessage existing = messageMapper.selectByClientMsgId(clientMsgId, target);
            if (existing != null) {
                saved.add(existing);
                continue;
            }
            CipherMessage record = new CipherMessage();
            record.setClientMsgId(clientMsgId);
            // 匿名提交与服务端都不保存任何发送者形式，只留会话标签。
            record.setConversationTag(tag);
            record.setRecallTokenHash(recallTokenHash);
            record.setSealed(true);
            record.setSessionId(message.getSessionId());
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
            record.setRecipientSignedPreKeyId(message.getRecipientSignedPreKeyId());
            record.setPadding(Boolean.TRUE.equals(message.getPadding()));
            record.setClientTime(message.getClientTime());
            record.setServerTime(now);
            record.setDelivered(false);
            record.setReadFlag(false);
            record.setRecalled(false);
            try {
                messageMapper.insert(record);
            } catch (DuplicateKeyException e) {
                CipherMessage duplicate = messageMapper.selectByClientMsgId(clientMsgId, target);
                if (duplicate != null) {
                    saved.add(duplicate);
                    continue;
                }
                throw e;
            }
            saved.add(record);
        }

        // 匿名提交没有「发送者其它设备」可以通知，只推给收信设备。
        for (CipherMessage record : saved) {
            try {
                BaseNotify<CipherMessage> notify =
                    BaseNotify.of(CommonConstants.NOTIFY_TYPE_NEW_MESSAGE, record);
                pushService.pushToDevice(toUsername, record.getTargetDeviceId(), notify);
            } catch (Exception e) {
                log.warn("匿名消息推送失败 id={}：{}", record.getId(), e.getMessage());
            }
        }
        return saved;
    }

    @Override
    public List<CipherMessage> history(String username, String peer, Long beforeId, int limit,
        String deviceId, List<String> tags) {
        ValidationUtil.validateUsername(peer);
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        String device = StringUtils.hasText(deviceId) ? deviceId.trim() : null;
        List<CipherMessage> messages = new ArrayList<>(safeLimit * 2);

        // 只按客户端提供的会话标签查询。协议断代后服务端没有任何用户名反查路径，
        // 因此 tags 为空时历史就是空的（旧数据已被归档清理）。
        List<String> safeTags = normalizeTags(tags);
        if (!safeTags.isEmpty()) {
            List<CipherMessage> tagged = device == null
                ? messageMapper.selectTagHistory(safeTags, username, peer, beforeId, safeLimit)
                : messageMapper.selectTagHistoryForDevice(safeTags, username, peer, device,
                    beforeId, safeLimit);
            if (tagged != null) {
                for (CipherMessage message : tagged) {
                    // 同步副本的方向由签名里的原始收发方决定（to_user 已被改写成我）；
                    // 其它副本：收信人是对端说明是我发的。
                    message.setFromMe(message.getSyncFromDevice() != null
                        ? username.equals(message.getSyncOriginalFrom())
                        : peer.equals(message.getToUsername()));
                }
                messages.addAll(tagged);
            }
        }

        // 同一逻辑消息只展示一行；设备查询已经选过副本，这里只做最终去重。
        Map<String, CipherMessage> unique = new LinkedHashMap<>();
        for (CipherMessage message : messages) {
            if (message == null) {
                continue;
            }
            String key = message.getClientMsgId() != null
                ? "c:" + message.getClientMsgId()
                : "i:" + message.getId();
            unique.putIfAbsent(key, message);
        }
        List<CipherMessage> merged = new ArrayList<>(unique.values());
        long cursor = clearCursor(username, peer);
        if (cursor > 0) {
            merged.removeIf(message -> message.getId() != null && message.getId() <= cursor);
        }
        merged.sort((left, right) -> {
            long leftId = left.getId() == null ? 0L : left.getId();
            long rightId = right.getId() == null ? 0L : right.getId();
            return Long.compare(rightId, leftId);
        });
        if (merged.size() > safeLimit) {
            merged = new ArrayList<>(merged.subList(0, safeLimit));
        }
        Collections.reverse(merged);
        return merged;
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
    public int markRead(String username, String peer, Long beforeId, List<String> tags) {
        ValidationUtil.validateUsername(peer);
        // 只按会话标签标记；没有标签就无从定位这段会话。
        List<String> safeTags = normalizeTags(tags);
        int affected = safeTags.isEmpty() ? 0
            : messageMapper.markReadByTags(safeTags, username, beforeId);
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
    public long countUnread(String username, String peer, List<String> tags) {
        ValidationUtil.validateUsername(peer);
        List<String> safeTags = normalizeTags(tags);
        if (safeTags.isEmpty()) {
            return 0L;
        }
        long cursor = clearCursor(username, peer);
        return messageMapper.countUnreadByTags(safeTags, username, cursor > 0 ? cursor : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearResult clearConversation(String username, String peer, boolean forBoth,
        List<String> tags) {
        ValidationUtil.validateUsername(peer);
        if (username.equals(peer)) {
            throw new UserInvalidException("不能清除与自己的会话");
        }
        List<String> safeTags = normalizeTags(tags);
        Long maxId = safeTags.isEmpty() ? null
            : messageMapper.maxTagConversationId(safeTags, username, peer);
        long cursor = maxId == null ? 0L : maxId;
        long now = System.currentTimeMillis();
        // 历史按 peer 维度的清除点过滤；未投递按标签维度的清除点过滤。
        advanceCursor(username, peer, cursor, now);
        for (String tag : safeTags) {
            advanceCursor(username, "tag:" + tag, cursor, now);
        }
        if (forBoth) {
            advanceCursor(peer, username, cursor, now);
            if (cursor > 0 && !safeTags.isEmpty()) {
                messageMapper.wipeTagConversation(safeTags, username, peer, cursor);
                for (String tag : safeTags) {
                    advanceCursor(peer, "tag:" + tag, cursor, now);
                }
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

    private Long max(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
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
    public CipherMessage recall(String username, String clientMsgId, String recallToken,
        List<String> tags) {
        if (!StringUtils.hasText(clientMsgId)) {
            throw new UserInvalidException("消息 ID 不能为空");
        }
        // 协议断代后没有任何发送者列：只能用客户端持有的会话标签定位本人参与的副本。
        List<String> safeTags = normalizeTags(tags);
        if (safeTags.isEmpty()) {
            throw new UserInvalidException("缺少会话标签");
        }
        CipherMessage sample = messageMapper.selectOwnedCopyByTags(safeTags, clientMsgId);
        if (sample == null) {
            throw new UserInvalidException("消息不存在");
        }
        if (Boolean.TRUE.equals(sample.getRecalled())) {
            throw new UserInvalidException("消息已撤回");
        }
        if (System.currentTimeMillis() - sample.getServerTime() > recallWindowSeconds * 1000L) {
            throw new UserInvalidException("超过撤回时限（" + recallWindowSeconds + " 秒）");
        }

        // 撤回只认一次性 token 的哈希，没有凭证就无法撤回。
        String presented = recallTokenHash(recallToken);
        if (presented == null || !presented.equals(sample.getRecallTokenHash())) {
            throw new UserInvalidException("撤回凭证无效");
        }
        long recalledAt = System.currentTimeMillis();
        int affected = messageMapper.recallByToken(clientMsgId, presented, recalledAt);
        if (affected == 0) {
            // 并发撤回：另一请求已处理
            throw new UserInvalidException("消息已撤回");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>(4);
            payload.put("clientMsgId", clientMsgId);
            payload.put("toUsername", sample.getToUsername());
            payload.put("recalledAt", recalledAt);
            BaseNotify<Map<String, Object>> notify =
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_MESSAGE_RECALLED, payload);
            // 撤回事件要通知双方<b>所有设备</b>，把各端渲染的消息都替换成占位。
            // sealed 消息的 sample.from 为 NULL，发件侧必须用 JWT 中的用户名推送。
            pushService.pushToUser(sample.getToUsername(), notify);
            pushService.pushToUser(username, notify);
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

    /**
     * 会话标签格式：客户端生成的 24~64 位 Base64URL，服务端不解释语义。
     * 非法标签直接丢弃（不阻断消息），避免旧客户端或半升级客户端发不出消息。
     */
    private static final Pattern CONVERSATION_TAG =
        Pattern.compile("^[A-Za-z0-9_-]{24,64}$");

    private static final int MAX_TAGS = 20;

    private String normalizeTag(String tag) {
        return StringUtils.hasText(tag) && CONVERSATION_TAG.matcher(tag).matches() ? tag : null;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> safe = new ArrayList<>(Math.min(tags.size(), MAX_TAGS));
        for (String tag : tags) {
            if (safe.size() >= MAX_TAGS) {
                break;
            }
            if (normalizeTag(tag) != null && !safe.contains(tag)) {
                safe.add(tag);
            }
        }
        return safe;
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
        String tag = normalizeTag(replica.getConversationTag());
        if (tag == null) {
            throw new UserInvalidException("同步副本必须携带会话标签");
        }
        // 标签是双方共享的随机值，知道标签即视为会话参与者。
        CipherMessage original =
            messageMapper.selectParticipantCopyByTag(replica.getClientMsgId(), tag);
        if (original == null) {
            throw new UserInvalidException("没有可同步的原消息");
        }
        // 没有发送者列：to_user 是自己说明这条是收到的，否则就是自己发出的。
        boolean sentByUser = !username.equals(original.getToUsername());
        CipherMessage existing = messageMapper.selectByClientMsgId(
            original.getClientMsgId(), target);
        if (existing != null) {
            return existing;
        }
        String other = sentByUser ? original.getToUsername()
            : (StringUtils.hasText(replica.getSyncOriginalFrom())
                ? replica.getSyncOriginalFrom() : null);
        if (other != null && original.getId() != null
            && original.getId() <= Math.max(clearCursor(username, other),
                clearCursor(username, "tag:" + tag))) {
            CipherMessage skipped = new CipherMessage();
            skipped.setClientMsgId(original.getClientMsgId());
            return skipped;
        }

        CipherMessage record = new CipherMessage();
        record.setClientMsgId(original.getClientMsgId());
        // 同一逻辑消息的所有副本共用撤回凭证哈希：用 token 撤回时一次覆盖全部副本。
        record.setRecallTokenHash(original.getRecallTokenHash());
        // 同步副本属于同一会话，标签必须跟着走，否则收信端按标签查不到它。
        record.setConversationTag(original.getConversationTag());
        record.setSealed(true);
        // 副本是给本账号自己的设备：收信人写自己，未投递查询才能命中。
        record.setToUsername(username);
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
            // 同步副本必须带转发设备对「原消息归属 + 内容」的签名。
            // 服务端不参与验签，但拒绝没有签名的旧格式，避免给客户端留下降级空间。
            if (!StringUtils.hasText(replica.getSyncSignature())) {
                throw new UserInvalidException("同步消息缺少来源签名");
            }
            if (!StringUtils.hasText(replica.getSyncOriginalFrom())
                || !StringUtils.hasText(replica.getSyncOriginalTo())
                || !StringUtils.hasText(replica.getSyncOriginalDevice())) {
                throw new UserInvalidException("同步消息缺少原消息归属");
            }
            if (replica.getSyncOriginalFrom().length() > 64
                || replica.getSyncOriginalTo().length() > 64
                || replica.getSyncOriginalDevice().length() > 64
                || replica.getSyncSignature().length() > 1024) {
                throw new UserInvalidException("同步来源签名字段过长");
            }
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
            record.setRecipientSignedPreKeyId(replica.getRecipientSignedPreKeyId());
            record.setPadding(Boolean.TRUE.equals(replica.getPadding()));
            record.setSyncOriginalFrom(replica.getSyncOriginalFrom());
            record.setSyncOriginalTo(replica.getSyncOriginalTo());
            record.setSyncOriginalDevice(replica.getSyncOriginalDevice());
            record.setSyncSignature(replica.getSyncSignature());
            record.setRecalled(false);
        }
        try {
            messageMapper.insert(record);
        } catch (DuplicateKeyException e) {
            CipherMessage duplicate = messageMapper.selectByClientMsgId(
                original.getClientMsgId(), target);
            if (duplicate != null) {
                return duplicate;
            }
            throw e;
        }
        if (!Boolean.TRUE.equals(record.getRecalled())) {
            // 同步副本使用的 OPK 属于目标设备（本账号的另一台设备），
            // 预留者是本账号当前这台同步发送设备。
            consumeOneTimePreKey(replica, username, target, username, fromDevice);
        }
        try {
            pushService.pushToDevice(username, target,
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_NEW_MESSAGE, record));
        } catch (Exception e) {
            log.warn("历史同步推送失败：{}", e.getMessage());
        }
        return record;
    }

    /**
     * 消息真正携带并使用了某个 OPK 后，删除它的预留记录。
     *
     * <p>只删除「由当前发信设备预留」的那一条：被盗 JWT 不能随便删掉
     * 其他设备预留的 OPK。删除失败只记日志，不影响消息落库。
     */
    private void consumeOneTimePreKey(CipherMessage message, String toUser, String targetDevice,
        String fromUser, String fromDevice) {
        if (preKeyMapper == null || message == null
            || !Boolean.TRUE.equals(message.getSenderUsedOneTimePreKey())
            || message.getConsumedOneTimePreKeyId() == null) {
            return;
        }
        try {
            preKeyMapper.deleteClaimedOneTimePreKey(toUser, targetDevice,
                message.getConsumedOneTimePreKeyId(), fromUser + "@" + fromDevice);
        } catch (Exception e) {
            log.warn("删除已使用的 OPK 失败：{}", e.getMessage());
        }
    }

    /**
     * 目标设备缺省为 default，与 /message 的约定一致。
     */
    private static String targetOf(CipherMessage message) {
        return StringUtils.hasText(message.getTargetDeviceId())
            ? message.getTargetDeviceId().trim() : UserKey.DEFAULT_DEVICE_ID;
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
