import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.SenderCertEnvelope;
import com.junesoon.mim.crypto.SyncEnvelope;
import com.junesoon.mim.crypto.TagEnvelope;
import com.junesoon.mim.model.SenderCertificateView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 导出 MiM 协议核心的基准向量，供 Rust 移植做逐字节对拍。
 *
 * <p>不是 Maven 源码（放在 tools/ 下），手动运行：
 * <pre>
 *   cd MIMCore && ../mvnw -q package
 *   javac -cp target/classes:$(slf4j jar) -d /tmp tools/ExportVectors.java
 *   java -cp /tmp:target/classes:$(slf4j jar) ExportVectors MIMCoreRust/tests/fixtures/vectors.json
 * </pre>
 *
 * <p>所有字符串都按 UTF-8 转成十六进制保存，避免 JSON 转义差异。
 */
public final class ExportVectors {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "vectors.json";
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        // ---------- HKDF ----------
        byte[] ikm = fill((byte) 0x01, 32);
        byte[] salt = fill((byte) 0x02, 32);
        String[][] hkdfCases = {
            {"", hex(ikm), "MIM/v2/initial", "32"},
            {hex(salt), hex(ikm), "MIM/v2/root", "32"},
            {hex(salt), hex(ikm), "MIM/v2/chain", "64"},
        };
        json.append("  \"hkdf\": [\n");
        for (int i = 0; i < hkdfCases.length; i++) {
            String[] c = hkdfCases[i];
            byte[] derived = Hkdf.derive(
                c[0].isEmpty() ? null : unhex(c[0]), unhex(c[1]), c[2], Integer.parseInt(c[3]));
            json.append("    {\"salt\":\"").append(c[0])
                .append("\",\"ikm\":\"").append(c[1])
                .append("\",\"info\":\"").append(c[2])
                .append("\",\"len\":").append(c[3])
                .append(",\"out\":\"").append(hex(derived)).append("\"}");
            json.append(i + 1 < hkdfCases.length ? ",\n" : "\n");
        }
        json.append("  ],\n");

        // ---------- AES-256-GCM ----------
        byte[] key = fill((byte) 0x03, 32);
        byte[] iv = fill((byte) 0x04, 12);
        String aad = "ratchet-pub|0|7";
        String plaintext = "端到端加密 spike";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        json.append("  \"aesgcm\": {\"key\":\"").append(hex(key))
            .append("\",\"iv\":\"").append(hex(iv))
            .append("\",\"aad\":\"").append(hex(aad.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"plaintext\":\"").append(hex(plaintext.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"ciphertext\":\"").append(hex(ct)).append("\"},\n");

        // ---------- 棘轮根密钥派生 ----------
        byte[] root = fill((byte) 0x05, 32);
        byte[] dh = fill((byte) 0x06, 32);
        byte[] prk = Hkdf.extract(root, dh);
        byte[] newRoot = Hkdf.expand(prk, "MIM/v2/root", 32);
        byte[] chain = Hkdf.expand(prk, "MIM/v2/chain", 32);
        json.append("  \"root_kdf\": {\"root\":\"").append(hex(root))
            .append("\",\"dh\":\"").append(hex(dh))
            .append("\",\"new_root\":\"").append(hex(newRoot))
            .append("\",\"chain\":\"").append(hex(chain)).append("\"},\n");

        // ---------- 链密钥推进 ----------
        byte[] mk = hmac(chain, (byte) 0x01);
        byte[] next = hmac(chain, (byte) 0x03);
        json.append("  \"chain\": {\"chain\":\"").append(hex(chain))
            .append("\",\"message_key\":\"").append(hex(mk))
            .append("\",\"next_chain\":\"").append(hex(next)).append("\"},\n");

        // ---------- AAD 序列化 ----------
        String aadText = "cmF0Y2hldC1wdWJsaWM" + "|" + 0 + "|" + 7;
        json.append("  \"aad\": {\"dh\":\"cmF0Y2hldC1wdWJsaWM\",\"pn\":0,\"n\":7,\"aad\":\"")
            .append(hex(aadText.getBytes(StandardCharsets.UTF_8))).append("\"},\n");

        // ---------- 标签信封 ----------
        String tag = "tagRustAAAAAAAAAAAAAAAAAAAA1";
        String body = "你还爱";
        json.append("  \"tag_envelope\": {\"tag\":\"").append(tag)
            .append("\",\"body\":\"").append(hex(body.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"packed\":\"")
            .append(hex(TagEnvelope.pack(tag, body).getBytes(StandardCharsets.UTF_8)))
            .append("\"},\n");

        // ---------- 同步信封（撤回凭证） ----------
        String recallToken = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        json.append("  \"sync_envelope\": {\"token\":\"").append(recallToken)
            .append("\",\"body\":\"").append(hex(body.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"packed\":\"")
            .append(hex(SyncEnvelope.pack(recallToken, body).getBytes(StandardCharsets.UTF_8)))
            .append("\"},\n");

        // ---------- 发送者证书规范化串 ----------
        SenderCertificateView cert = new SenderCertificateView();
        cert.setUsername("alice");
        cert.setDeviceId("alice-desktop");
        cert.setIdentityKey("QUJDREVGRw==");
        cert.setIssuedAt(1795056000000L);
        cert.setExpiresAt(1795142400000L);
        cert.setKeyId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        cert.setSignature("c2lnbmF0dXJl");
        json.append("  \"cert_canonical\": {\"username\":\"alice\",\"device\":\"alice-desktop\",")
            .append("\"identity\":\"QUJDREVGRw==\",\"issued\":1795056000000,")
            .append("\"expires\":1795142400000,")
            .append("\"key_id\":\"").append(cert.getKeyId()).append("\",")
            .append("\"canonical\":\"")
            .append(hex(cert.canonical().getBytes(StandardCharsets.UTF_8))).append("\"},\n");

        // ---------- SHA-256 十六进制（撤回凭证哈希的格式） ----------
        json.append("  \"sha256_hex\": {\"input\":\"")
            .append(hex(recallToken.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"hash\":\"").append(sha256Hex(recallToken)).append("\"}\n");

        json.append("}\n");
        Files.write(Paths.get(out), json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
    }

    private static byte[] hmac(byte[] key, byte marker) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(new byte[] {marker});
    }

    private static String sha256Hex(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8));
        return hex(hash);
    }

    private static byte[] fill(byte value, int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
