package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 服务端证书公钥的固定（TOFU）测试。
 *
 * <p>固定之后服务端换钥必须被拒，否则被攻破的服务端可以换公钥伪造证书。
 *
 * [INPUT]: 只依赖临时目录
 * [OUTPUT]: 验证首次固定、重启后读回、换钥保留旧值
 * [POS]: 客户端信任锚的持久化行为
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class ServerCertPinTest {

    @Test
    @DisplayName("首次固定后写入文件，重启后仍读到同一把")
    void pinsAndReloads() throws Exception {
        Path dir = Files.createTempDirectory("mim-cert-pin");
        ServerCertPin first = new ServerCertPin(dir);
        assertNull(first.resolve(null, null), "还没固定过时不应凭空返回公钥");

        ServerCertPin.Pin pin = first.resolve("kid-1", "pub-1");
        assertNotNull(pin);
        assertEquals("kid-1", pin.getKeyId());
        assertEquals("pub-1", pin.getPublicKey());

        ServerCertPin reloaded = new ServerCertPin(dir);
        ServerCertPin.Pin again = reloaded.resolve(null, null);
        assertNotNull(again, "固定值必须持久化");
        assertEquals("pub-1", again.getPublicKey());
    }

    @Test
    @DisplayName("服务端换钥时保留旧固定值，新证书会因此验签失败")
    void keepsOldKeyWhenServerRotates() throws Exception {
        Path dir = Files.createTempDirectory("mim-cert-pin-rotate");
        ServerCertPin pin = new ServerCertPin(dir);
        pin.resolve("kid-1", "pub-1");

        ServerCertPin.Pin resolved = pin.resolve("kid-2", "pub-2");
        assertEquals("kid-1", resolved.getKeyId(), "必须保留旧 keyId");
        assertEquals("pub-1", resolved.getPublicKey(), "必须保留旧公钥");
    }
}
