package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.feign.ChatFeign;
import com.junesoon.mim.feign.DeviceFeign;
import com.junesoon.mim.feign.MessageFeign;
import com.junesoon.mim.model.BaseNotify;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.ChatUser;
import com.junesoon.mim.model.CipherMessage;
import com.junesoon.mim.service.MessageService.DecryptedMessage;
import com.junesoon.mim.websocket.WSClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 把本机已经解开的历史，重新加密给自己的另一台设备。
 *
 * <p>每个会话只带最近 50 条。本机没有原文的消息补不了，这是端到端加密的边界。
 */
@Slf4j
public final class HistorySync {

    private static final int PAGE = 50;
    private static final int BATCH = 50;

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static volatile WSClient bound;

    private HistorySync() {
    }

    /**
     * 在当前这条连接上监听「请把历史给我」的通知。换连接后要再调用一次。
     */
    public static void arm() {
        WSClient client = WSClient.getInstance();
        if (bound == client) {
            return;
        }
        client.addListener(HistorySync::onNotify);
        bound = client;
    }

    /**
     * 登录后：请其他设备补给我，同时把我有的原文补给它们。
     */
    public static void requestInBackground() {
        Thread worker = new Thread(() -> {
            request();
            forwardToSiblings();
        }, "history-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void onNotify(BaseNotify<?> notify) {
        if (notify == null || notify.getType() == null
            || notify.getType() != WSClient.NOTIFY_HISTORY_SYNC) {
            return;
        }
        if (!(notify.getData() instanceof Map)) {
            return;
        }
        Object deviceId = ((Map<?, ?>) notify.getData()).get("deviceId");
        if (deviceId == null) {
            return;
        }
        String target = deviceId.toString();
        String self = KeyManager.getInstance().deviceId();
        if (target.equals(self)) {
            return;
        }
        Thread worker = new Thread(() -> forward(target), "history-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void request() {
        try {
            ObjectRestResponse<Boolean> response = Request.connector(MessageFeign.class).requestSync();
            if (response == null || response.getStatus() != 200) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                log.warn("请求其他设备转发历史失败：{}", message);
            }
        } catch (Exception e) {
            log.warn("请求其他设备转发历史失败：{}", e.getMessage());
        }
    }

    private static void forwardToSiblings() {
        String self = KeyManager.getInstance().deviceId();
        for (String deviceId : siblingDevices()) {
            if (deviceId == null || deviceId.equals(self)) {
                continue;
            }
            forward(deviceId);
        }
    }

    private static void forward(String targetDeviceId) {
        Object lock = LOCKS.computeIfAbsent(targetDeviceId, key -> new Object());
        synchronized (lock) {
            Set<String> done = loadDone(targetDeviceId);
            List<CipherMessage> batch = new ArrayList<>();
            int forwarded = 0;
            for (String peer : peers()) {
                List<DecryptedMessage> page = MessageService.getInstance().history(peer, null, PAGE);
                for (DecryptedMessage message : page) {
                    String clientMsgId = message.getClientMsgId();
                    if (clientMsgId == null || done.contains(clientMsgId)) {
                        continue;
                    }
                    CipherMessage replica = toReplica(targetDeviceId, message);
                    if (replica == null) {
                        continue;
                    }
                    batch.add(replica);
                    if (batch.size() >= BATCH) {
                        forwarded += flush(targetDeviceId, batch, done);
                    }
                }
            }
            forwarded += flush(targetDeviceId, batch, done);
            if (forwarded > 0) {
                log.info("已向设备 {} 转发 {} 条历史", targetDeviceId, forwarded);
            }
        }
    }

    private static CipherMessage toReplica(String targetDeviceId, DecryptedMessage message) {
        if (message.isRecalled()) {
            CipherMessage tombstone = new CipherMessage();
            tombstone.setClientMsgId(message.getClientMsgId());
            tombstone.setTargetDeviceId(targetDeviceId);
            return tombstone;
        }
        if (message.isDecryptFailed()) {
            return null;
        }
        String text = message.getPlaintext();
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return MessageService.getInstance().wrapForOwnDevice(targetDeviceId, text,
                message.getClientMsgId(), message.getTimestamp());
        } catch (IdentityException e) {
            log.warn("转发消息 {} 失败：{}", message.getClientMsgId(), e.getMessage());
            return null;
        }
    }

    private static int flush(String targetDeviceId, List<CipherMessage> batch, Set<String> done) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<CipherMessage> sending = new ArrayList<>(batch);
        batch.clear();
        try {
            ObjectRestResponse<List<CipherMessage>> response =
                Request.connector(MessageFeign.class).sync(sending);
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                log.warn("上传历史副本失败：{}", message);
                return 0;
            }
            int accepted = 0;
            for (CipherMessage item : response.getData()) {
                if (item != null && item.getClientMsgId() != null) {
                    done.add(item.getClientMsgId());
                    accepted++;
                }
            }
            saveDone(targetDeviceId, done);
            return accepted;
        } catch (Exception e) {
            log.warn("上传历史副本失败：{}", e.getMessage());
            return 0;
        }
    }

    private static List<String> peers() {
        List<String> peers = new ArrayList<>();
        try {
            List<ChatContact> groups = Request.connector(ChatFeign.class).getContacts();
            if (groups == null) {
                return peers;
            }
            for (ChatContact group : groups) {
                if (group.getUsers() == null) {
                    continue;
                }
                for (ChatUser user : group.getUsers()) {
                    if (user.getUsername() != null && !user.getUsername().isEmpty()) {
                        peers.add(user.getUsername());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取联系人失败，跳过历史转发：{}", e.getMessage());
        }
        return peers;
    }

    private static List<String> siblingDevices() {
        List<String> devices = new ArrayList<>();
        try {
            ObjectRestResponse<Map<String, Object>> response =
                Request.connector(DeviceFeign.class).list();
            if (response == null || response.getData() == null) {
                return devices;
            }
            Object raw = response.getData().get("devices");
            if (!(raw instanceof List)) {
                return devices;
            }
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Object id = ((Map<?, ?>) item).get("deviceId");
                    if (id != null) {
                        devices.add(id.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取设备列表失败，跳过历史转发：{}", e.getMessage());
        }
        return devices;
    }

    private static Set<String> loadDone(String targetDeviceId) {
        Path file = stamp(targetDeviceId);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashSet<>();
        }
        try {
            return new LinkedHashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取已转发记录失败：{}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    private static void saveDone(String targetDeviceId, Set<String> done) {
        Path file = stamp(targetDeviceId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new HashSet<>(done), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存已转发记录失败：{}", e.getMessage());
        }
    }

    private static Path stamp(String targetDeviceId) {
        String user = ApplicatonStore.getUsername() == null ? "anon" : ApplicatonStore.getUsername();
        String self = KeyManager.getInstance().deviceId();
        String name = safe(user) + "__" + safe(self) + "__" + safe(targetDeviceId) + ".ids";
        return Paths.get(System.getProperty("user.home"), ".mim", "sync", name);
    }

    private static String safe(String value) {
        if (value == null) {
            return "none";
        }
        return value.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }
}
