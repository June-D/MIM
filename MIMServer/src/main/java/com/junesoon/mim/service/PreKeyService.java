package com.junesoon.mim.service;

import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import java.util.List;

/**
 * 预密钥包服务（X3DH 的密钥分发侧）。
 */
public interface PreKeyService {

    /**
     * 上传/更新本设备的签名预密钥。
     *
     * <p>服务端不验签：签名由客户端生成、由<b>接收方</b>用已固定的身份公钥验证。
     * 服务端若也验签，等于让它参与信任链，反而削弱了端到端的含义。
     */
    SignedPreKey uploadSignedPreKey(String username, String deviceId, SignedPreKey request);

    /**
     * 批量上传本设备的一次性预密钥。
     *
     * @return 实际新增数量（重复编号被忽略）
     */
    int uploadOneTimePreKeys(String username, String deviceId, List<OneTimePreKey> keys);

    /**
     * 取某用户<b>所有有效设备</b>的预密钥包。
     *
     * <p>多设备下发起方必须为每个设备各建一个会话，因此这里返回列表。
     * 每台设备的一个一次性预密钥会<b>预留</b>给 requester；只有消息真正
     * 使用后才删除，未使用会在 TTL 后释放。
     *
     * @param requester 请求者标识，形如 {@code username@deviceId}
     */
    List<PreKeyBundle> fetchBundles(String username, String requester);

    /**
     * 只取一台设备的预密钥包。
     */
    PreKeyBundle fetchBundle(String username, String deviceId, String requester);

    /**
     * 本设备剩余可用的一次性预密钥数量。
     */
    int countOneTimePreKeys(String username, String deviceId);

    /**
     * 单个设备的预密钥包。
     */
    final class PreKeyBundle {
        private final String username;
        private final String deviceId;
        private final String identityPublicKey;
        private final Integer identityKeyVersion;
        private final Integer signedPreKeyId;
        private final String signedPreKeyPublic;
        private final String signedPreKeySignature;
        private final Integer oneTimePreKeyId;
        private final String oneTimePreKeyPublic;
        private final Boolean supportsRecallToken;
        private final Boolean supportsSenderCert;

        public PreKeyBundle(String username, String deviceId, String identityPublicKey,
            Integer identityKeyVersion, Integer signedPreKeyId, String signedPreKeyPublic,
            String signedPreKeySignature, Integer oneTimePreKeyId, String oneTimePreKeyPublic,
            Boolean supportsRecallToken, Boolean supportsSenderCert) {
            this.username = username;
            this.deviceId = deviceId;
            this.identityPublicKey = identityPublicKey;
            this.identityKeyVersion = identityKeyVersion;
            this.signedPreKeyId = signedPreKeyId;
            this.signedPreKeyPublic = signedPreKeyPublic;
            this.signedPreKeySignature = signedPreKeySignature;
            this.oneTimePreKeyId = oneTimePreKeyId;
            this.oneTimePreKeyPublic = oneTimePreKeyPublic;
            this.supportsRecallToken = supportsRecallToken;
            this.supportsSenderCert = supportsSenderCert;
        }

        public String getUsername() {
            return username;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getIdentityPublicKey() {
            return identityPublicKey;
        }

        public Integer getIdentityKeyVersion() {
            return identityKeyVersion;
        }

        public Integer getSignedPreKeyId() {
            return signedPreKeyId;
        }

        public String getSignedPreKeyPublic() {
            return signedPreKeyPublic;
        }

        public String getSignedPreKeySignature() {
            return signedPreKeySignature;
        }

        public Integer getOneTimePreKeyId() {
            return oneTimePreKeyId;
        }

        public String getOneTimePreKeyPublic() {
            return oneTimePreKeyPublic;
        }

        public boolean hasOneTimePreKey() {
            return oneTimePreKeyId != null && oneTimePreKeyPublic != null;
        }

        /**
         * 该设备能否接收「带加密撤回凭证」的同步副本。
         * 历史同步据此决定是否把凭证包进密文；旧客户端为 false。
         */
        public Boolean getSupportsRecallToken() {
            return supportsRecallToken;
        }

        /**
         * 该设备能否解析首条消息里的发送者证书（Phase 2b）。
         * 未声明能力的旧客户端为 false，继续收明文发送者格式。
         */
        public Boolean getSupportsSenderCert() {
            return supportsSenderCert;
        }
    }
}
