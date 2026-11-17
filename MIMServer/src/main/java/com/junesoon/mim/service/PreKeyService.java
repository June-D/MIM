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
     * 每个包会各自消耗目标设备的一个一次性预密钥。
     */
    List<PreKeyBundle> fetchBundles(String username);

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

        public PreKeyBundle(String username, String deviceId, String identityPublicKey,
            Integer identityKeyVersion, Integer signedPreKeyId, String signedPreKeyPublic,
            String signedPreKeySignature, Integer oneTimePreKeyId, String oneTimePreKeyPublic) {
            this.username = username;
            this.deviceId = deviceId;
            this.identityPublicKey = identityPublicKey;
            this.identityKeyVersion = identityKeyVersion;
            this.signedPreKeyId = signedPreKeyId;
            this.signedPreKeyPublic = signedPreKeyPublic;
            this.signedPreKeySignature = signedPreKeySignature;
            this.oneTimePreKeyId = oneTimePreKeyId;
            this.oneTimePreKeyPublic = oneTimePreKeyPublic;
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
    }
}
