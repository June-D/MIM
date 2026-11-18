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
     * 带能力协商的上传。两个能力位为空都按 false 处理：
     * supportsPadding 表示能否接收填充消息，supportsRecallToken
     * 表示能否在设备间同步副本里接收加密的撤回凭证。
     */
    KeyBundle upload(String username, String deviceId, String publicKey, String algorithm,
        String password, Boolean supportsPadding, Boolean supportsRecallToken);

    /**
     * 取指定设备（缺省为 default）的公钥。
     */
    KeyBundle get(String username, String deviceId);

    /**
     * 取某用户所有有效设备的公钥，发消息时按设备逐个加密需要它。
     */
    List<KeyBundle> getAllDevices(String username);
}
