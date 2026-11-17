package com.junesoon.mim.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对端设备身份发生变化，需要用户核对后显式确认。
 *
 * <p>与普通 {@link IdentityException} 区分开，是因为它可以被用户处置：
 * 界面展示每台设备的新旧指纹，用户线下核对无误后确认，才更新本机固定值。
 *
 * <p>之所以是「一组」而不是单个公钥：一次发送会同时拿到对方所有设备的预密钥包，
 * 可能同时存在「新增设备」和「设备换钥」。只报第一条会让用户反复确认。
 */
public class TrustChangedException extends IdentityException {

    private static final long serialVersionUID = 1L;

    /**
     * 需要确认的原因。
     */
    public enum Reason {
        /** 已固定设备换了公钥 */
        KEY_CHANGED,
        /** 已固定用户新增了一台设备 */
        NEW_DEVICE
    }

    /**
     * 单台设备的问题。
     */
    public static final class Issue {
        private final String deviceId;
        private final Reason reason;
        private final String previousFingerprint;
        private final String currentFingerprint;
        private final String newPublicKey;

        public Issue(String deviceId, Reason reason, String previousFingerprint,
            String currentFingerprint, String newPublicKey) {
            this.deviceId = deviceId;
            this.reason = reason;
            this.previousFingerprint = previousFingerprint;
            this.currentFingerprint = currentFingerprint;
            this.newPublicKey = newPublicKey;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public Reason getReason() {
            return reason;
        }

        public String getPreviousFingerprint() {
            return previousFingerprint;
        }

        public String getCurrentFingerprint() {
            return currentFingerprint;
        }

        public String getNewPublicKey() {
            return newPublicKey;
        }
    }

    private final String peer;

    private final List<Issue> issues;

    public TrustChangedException(String peer, List<Issue> issues, String message) {
        super(message);
        this.peer = peer;
        this.issues = issues == null
            ? Collections.<Issue>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public String getPeer() {
        return peer;
    }

    public List<Issue> getIssues() {
        return issues;
    }

    /**
     * 兼容旧界面的单值访问器：返回第一条问题。
     */
    public String getDeviceId() {
        return issues.isEmpty() ? null : issues.get(0).getDeviceId();
    }

    public String getPreviousFingerprint() {
        return issues.isEmpty() ? null : issues.get(0).getPreviousFingerprint();
    }

    public String getCurrentFingerprint() {
        return issues.isEmpty() ? null : issues.get(0).getCurrentFingerprint();
    }

    public String getNewPublicKey() {
        return issues.isEmpty() ? null : issues.get(0).getNewPublicKey();
    }

    public boolean isNewDevice() {
        return !issues.isEmpty() && issues.get(0).getReason() == Reason.NEW_DEVICE;
    }
}
