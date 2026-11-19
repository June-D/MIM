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

    private static final String AIK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed1aaac79c7a0a07e89016ca9fd088faecb674bfdb364c61772b304dc47656b0a14403420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String AIK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String AEK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420cefa80706633c3cfaf970186a16cbeae76fe19d5d8f75179e6e60616d99b4e31a14403420004025268a7415a2af79ea1bbaca974d02d2ce9f4729304a222b9b9017e29bc6ef6c53d82ec9a9dd5fe31e40b9bf27896e5f9df94a9b2a4d62df3b42968ba0d1f36";
    private static final String AEK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004025268a7415a2af79ea1bbaca974d02d2ce9f4729304a222b9b9017e29bc6ef6c53d82ec9a9dd5fe31e40b9bf27896e5f9df94a9b2a4d62df3b42968ba0d1f36";
    private static final String BIK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104206a3364f2700f5aab8d9e81f56c3776a167fcd66f2c62299274afb64e9ad531dea14403420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String BIK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String BSPK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420938517594ecaf3587286695e0e675f1177c0278b6281f3517c83ace4ab8bf0e6a14403420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String BSPK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String BOPK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420c998cc833a3669a48ad6de76655b06aa568dc152a73517675afe94da3bf444d5a144034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String BOPK_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String AEK2_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420db9918aa00a325c358939f01c7657587656f46c8bf97f05d84a396ca933f3df0a144034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
    private static final String AEK2_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";

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

        // ---------- P-256 ECDH + X3DH ----------
        java.security.PrivateKey aIk = com.junesoon.mim.crypto.DhKeyUtil.decodePrivateKey(unhex(AIK_PRIV));
        java.security.PrivateKey aEk = com.junesoon.mim.crypto.DhKeyUtil.decodePrivateKey(unhex(AEK_PRIV));
        java.security.PrivateKey aEk2 = com.junesoon.mim.crypto.DhKeyUtil.decodePrivateKey(unhex(AEK2_PRIV));
        java.security.PublicKey bIk = com.junesoon.mim.crypto.DhKeyUtil
            .decodePublicKey(Base64.getEncoder().encodeToString(unhex(BIK_PUB)));
        java.security.PublicKey bSpk = com.junesoon.mim.crypto.DhKeyUtil
            .decodePublicKey(Base64.getEncoder().encodeToString(unhex(BSPK_PUB)));
        java.security.PublicKey bOpk = com.junesoon.mim.crypto.DhKeyUtil
            .decodePublicKey(Base64.getEncoder().encodeToString(unhex(BOPK_PUB)));
        byte[] dh1 = com.junesoon.mim.crypto.DhKeyUtil.agree(aIk, bSpk);
        byte[] dh2 = com.junesoon.mim.crypto.DhKeyUtil.agree(aEk, bIk);
        byte[] dh3 = com.junesoon.mim.crypto.DhKeyUtil.agree(aEk, bSpk);
        byte[] dh4 = com.junesoon.mim.crypto.DhKeyUtil.agree(aEk, bOpk);
        byte[] shared = concat(dh1, dh2, dh3, dh4);
        byte[] initialKey = Hkdf.derive(null, shared, "MIM/v2/initial", 32);
        json.append("  \"x3dh\": {\"aik_priv\":\"").append(AIK_PRIV)
            .append("\",\"aek_priv\":\"").append(AEK_PRIV)
            .append("\",\"bik_pub\":\"").append(BIK_PUB)
            .append("\",\"bspk_pub\":\"").append(BSPK_PUB)
            .append("\",\"bopk_pub\":\"").append(BOPK_PUB)
            .append("\",\"dh1\":\"").append(hex(dh1))
            .append("\",\"dh2\":\"").append(hex(dh2))
            .append("\",\"dh3\":\"").append(hex(dh3))
            .append("\",\"dh4\":\"").append(hex(dh4))
            .append("\",\"initial_key\":\"").append(hex(initialKey)).append("\"},\n");

        byte[] dhOut = com.junesoon.mim.crypto.DhKeyUtil.agree(aEk2, bIk);
        byte[] dhPrk = Hkdf.extract(root, dhOut);
        byte[] dhRoot = Hkdf.expand(dhPrk, "MIM/v2/root", 32);
        byte[] dhChain = Hkdf.expand(dhPrk, "MIM/v2/chain", 32);
        json.append("  \"dh_ratchet\": {\"aek2_priv\":\"").append(AEK2_PRIV)
            .append("\",\"bik_pub\":\"").append(BIK_PUB)
            .append("\",\"dh_out\":\"").append(hex(dhOut))
            .append("\",\"root\":\"").append(hex(root))
            .append("\",\"new_root\":\"").append(hex(dhRoot))
            .append("\",\"chain\":\"").append(hex(dhChain)).append("\"},\n");

        // ---------- 发送者证书（真实 RSA 签名） ----------
        SenderCertificateView rsaCert = new SenderCertificateView();
        rsaCert.setUsername("alice");
        rsaCert.setDeviceId("alice-desktop");
        rsaCert.setIdentityKey("QUJDREVGRw==");
        rsaCert.setIssuedAt(1795056000000L);
        rsaCert.setExpiresAt(1795142400000L);
        rsaCert.setKeyId("518d14873ea4aefc55cdf811c54aed94a9905faa92fa7938016346ffb90699c2");
        rsaCert.setSignature("sMbbUAfqqjIA8Kosy1K1L9LQqbnGOPmcxn+IVFOUb6IC78oxfuyD8PFVdqteaz7VYkPtmXyb4JHXLr/GmGYocP3U5sHquvNiaWxX9wQrvq3NSUI61Wy552cTWNul03F6VRhgFimY7FbEtj8bhG5gS8WFJHK/kIsZTw0rOzSilKVMnvx8CZzNphZ3dNtnFVyfWvpeyXxlFmcGNIerLSZQ0q/choIuRKkVZT2h1BOwDfgWpGQ1e3km8/vxtuwTsnZpVIc8sKYZQrKLyr4D/LxoVg1x/GFBzorlnZi+1ku7LgFgMvEaK81i/1ETPUTRCyQqUVA6Gw8qtjUvn+wadX/nAw==");
        byte[] certCanonical = rsaCert.canonical().getBytes(StandardCharsets.UTF_8);
        json.append("  \"cert_rsa\": {\"public_key\":\"30820122300d06092a864886f70d01010105000382010f003082010a0282010100b957bd58c2ad1f8a5caf4759f6bc4472d0013035e3fbc5a247d2e7efc79d863cb2fe7a99c16aa90dead69bd6c3a76fd88c606e94d21fd3f8feaa1d51470c19ae0c655017dfc6d5c8b25d4d007159f539ba2c3a125d767891050db7e850086cfb3c9d5e70c2c9f36dfea009f3c0b77bdd5bc5d360cab1b7f916a94a8e5e216e86878d4fe835c4770a2520f2b94d08bdb00e52ed91b8ecc014c91bd377c1235964da5dd41ba62389e9af020ad46e0f8ea13f6433e656317365bbe80920ad8ce17ba53ead0cd3cc0db3dbe3a8c03ee4363e0d82801eb3ddb337d258cef1800ba8f1dd3b9eebe065da78d3f5c65437f53e8b8ceb51d0e0fc7c5a0c2aa180966cf9930203010001\",\"now\":1795070000000,")
            .append("\"canonical\":\"").append(hex(certCanonical)).append("\",")
            .append("\"cert\":{\"username\":\"alice\",\"device_id\":\"alice-desktop\",")
            .append("\"identity_key\":\"QUJDREVGRw==\",\"issued_at\":1795056000000,")
            .append("\"expires_at\":1795142400000,\"key_id\":\"518d14873ea4aefc55cdf811c54aed94a9905faa92fa7938016346ffb90699c2\",")
            .append("\"signature\":\"sMbbUAfqqjIA8Kosy1K1L9LQqbnGOPmcxn+IVFOUb6IC78oxfuyD8PFVdqteaz7VYkPtmXyb4JHXLr/GmGYocP3U5sHquvNiaWxX9wQrvq3NSUI61Wy552cTWNul03F6VRhgFimY7FbEtj8bhG5gS8WFJHK/kIsZTw0rOzSilKVMnvx8CZzNphZ3dNtnFVyfWvpeyXxlFmcGNIerLSZQ0q/choIuRKkVZT2h1BOwDfgWpGQ1e3km8/vxtuwTsnZpVIc8sKYZQrKLyr4D/LxoVg1x/GFBzorlnZi+1ku7LgFgMvEaK81i/1ETPUTRCyQqUVA6Gw8qtjUvn+wadX/nAw==\"}},\n");

        // ---------- 证书信封（7 段 / 带会话标签的 8 段） ----------
        String certBody = "首条消息正文";
        String certTag = "tagRustAAAAAAAAAAAAAAAAAAAA1";
        json.append("  \"cert_envelope\": {\"body\":\"").append(hex(certBody.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"tag\":\"").append(certTag).append("\",")
            .append("\"packed7\":\"").append(hex(SenderCertEnvelope.pack(rsaCert, null, certBody).getBytes(StandardCharsets.UTF_8)))
            .append("\",\"packed8\":\"").append(hex(SenderCertEnvelope.pack(rsaCert, certTag, certBody).getBytes(StandardCharsets.UTF_8)))
            .append("\"},\n");

        // ---------- SHA-256 十六进制（撤回凭证哈希的格式） ----------
        json.append("  \"sha256_hex\": {\"input\":\"")
            .append(hex(recallToken.getBytes(StandardCharsets.UTF_8)))
            .append("\",\"hash\":\"").append(sha256Hex(recallToken)).append("\"}\n");

        json.append("}\n");
        Files.write(Paths.get(out), json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part == null ? 0 : part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null) {
                continue;
            }
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
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
