package com.junesoon.mim.service;

import com.junesoon.mim.responses.KeyBundle;
import java.util.List;

public interface KeyService {

    /**
     * 上传/更新本设备的身份公钥。
     */
    KeyBundle upload(String username, String deviceId, String publicKey, String algorithm,
        String password);

    /**
     * 取指定设备（缺省为 default）的公钥。
     */
    KeyBundle get(String username, String deviceId);

    /**
     * 取某用户所有有效设备的公钥，发消息时按设备逐个加密需要它。
     */
    List<KeyBundle> getAllDevices(String username);
}
