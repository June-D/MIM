package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.websocket.WSClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 登出 / 切换账号。
 *
 * <p>这是最容易做错的一处：只清 UI 状态而忘了重置加密单例，
 * 会导致<b>新账号沿用上一个账号的身份密钥与会话棘轮</b>——
 * 消息会用错密钥加密，对方的解密必然失败，而且现象非常隐蔽
 * （消息发出去了，但对端显示"无法解密"）。
 *
 * <p>因此这里按「连接 → 密钥 → 视图」的顺序彻底清理：
 * <ol>
 *   <li>断开 WebSocket，避免旧账号继续收推送；</li>
 *   <li>重置全部加密单例（身份、预密钥、会话棘轮、信任库）；</li>
 *   <li>清空登录态与本地视图缓存。</li>
 * </ol>
 *
 * <p>注意：<b>磁盘上的密钥文件不删除</b>。切回原账号时还能继续解密历史消息，
 * 这是刻意的设计——登出不等于销毁本机密钥。
 */
@Slf4j
public final class SessionManager {

    private SessionManager() {
    }

    /**
     * 执行登出。幂等，重复调用无副作用。
     */
    public static void logout() {
        String username = ApplicatonStore.getUsername();

        // 1. 先断连接：避免清理过程中还有推送进来写状态
        try {
            WSClient.discard();
        } catch (Exception e) {
            log.warn("关闭 WebSocket 失败：{}", e.getMessage());
        }

        // 2. 重置加密单例。顺序不重要，但必须全部覆盖
        try {
            MessageService.getInstance().reset();
        } catch (Exception e) {
            log.warn("重置会话状态失败：{}", e.getMessage());
        }
        try {
            PreKeyManager.getInstance().reset();
        } catch (Exception e) {
            log.warn("重置预密钥失败：{}", e.getMessage());
        }
        try {
            // KeyManager.reset() 同时会重置 DeviceIdentity：
            // 否则切到新账号后仍沿用旧账号的设备标识，两个账号会共用一份密钥记录
            KeyManager.getInstance().reset();
        } catch (Exception e) {
            log.warn("重置身份密钥失败：{}", e.getMessage());
        }

        // 3. 清登录态与视图缓存
        ApplicatonStore.clearUserInfo();

        log.info("已登出{}", username == null ? "" : "：" + username);
    }
}
