package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * 用真实 {@link RatchetService} 导出双棘轮状态机基准向量。
 *
 * <p>放在 test 源码里，是为了能注入固定密钥与 IV 并读取 {@link SessionState}
 * 的全部字段；生产代码只多了一个包内可见的注入点，行为不变。
 *
 * <p>运行：
 * <pre>
 *   cd MIMCore &amp;&amp; ../mvnw -q test-compile
 *   ../mvnw -q dependency:build-classpath -Dmdep.outputFile=/tmp/core-test-cp.txt
 *   java -cp "target/test-classes:target/classes:$(cat /tmp/core-test-cp.txt)" \
 *        com.junesoon.mim.crypto.RatchetVectors ../MIMCoreRust/tests/fixtures/ratchet-vectors.json
 * </pre>
 *
 * <p>输出必须完全可复现：所有随机量（临时密钥、换步密钥、IV）都由固定序列注入。
 */
public final class RatchetVectors {

    private static final String AIK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed1aaac79c7a0a07e89016ca9fd088faecb674bfdb364c61772b304dc47656b0a14403420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String AIK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String AEK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420cefa80706633c3cfaf970186a16cbeae76fe19d5d8f75179e6e60616d99b4e31a14403420004025268a7415a2af79ea1bbaca974d02d2ce9f4729304a222b9b9017e29bc6ef6c53d82ec9a9dd5fe31e40b9bf27896e5f9df94a9b2a4d62df3b42968ba0d1f36";
    private static final String AEK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004025268a7415a2af79ea1bbaca974d02d2ce9f4729304a222b9b9017e29bc6ef6c53d82ec9a9dd5fe31e40b9bf27896e5f9df94a9b2a4d62df3b42968ba0d1f36";
    private static final String AEK2_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420db9918aa00a325c358939f01c7657587656f46c8bf97f05d84a396ca933f3df0a144034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
    private static final String AEK2_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
    private static final String BIK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104206a3364f2700f5aab8d9e81f56c3776a167fcd66f2c62299274afb64e9ad531dea14403420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String BIK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String BSPK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420938517594ecaf3587286695e0e675f1177c0278b6281f3517c83ace4ab8bf0e6a14403420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String BSPK_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String BOPK_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420c998cc833a3669a48ad6de76655b06aa568dc152a73517675afe94da3bf444d5a144034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String BOPK_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String R1_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104202080e37a085bc764fd7fc6b3a258b762b5a4145d4cbe3c4bd34542977a380be2a14403420004c321894ca89f752b1314eae7e15ccddff0ad2c6ffe5846a880b9ac821a74e726cb04f81e9c40548542943018a7e2094ed0540110a4758ae770d81c405a99f9ec";
    private static final String R1_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004c321894ca89f752b1314eae7e15ccddff0ad2c6ffe5846a880b9ac821a74e726cb04f81e9c40548542943018a7e2094ed0540110a4758ae770d81c405a99f9ec";
    private static final String R2_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ce5766aa4468b9fa90b4fd1443ee8f5baabd747c8f577b4f086a38ef080bfa48a14403420004bfac3d9ef6614c8b69ac4512b1ee761484e1ef7be64f1a3b6e861551852f28e1aded9d0d0c3d810035f42ef1258ef90f878cf16762fc03d232a78aff025ce369";
    private static final String R2_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004bfac3d9ef6614c8b69ac4512b1ee761484e1ef7be64f1a3b6e861551852f28e1aded9d0d0c3d810035f42ef1258ef90f878cf16762fc03d232a78aff025ce369";
    private static final String R3_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420c31a1676513b76965d5d26e4cb25927bcc1ebfb0f7b6e3e0e754decbfccc9706a14403420004684869f155766b987b742ecb552c3590d82e247093f791e7353870151a8c31fca902d0635d5b48a6438a37f388e05b24cfe6b5a5b164659b471c8de83202bfe5";
    private static final String R3_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004684869f155766b987b742ecb552c3590d82e247093f791e7353870151a8c31fca902d0635d5b48a6438a37f388e05b24cfe6b5a5b164659b471c8de83202bfe5";

    /** 换步密钥的顺序：发起方临时密钥、随后三次 DH 棘轮（B、A、B）。 */
    private static final String[] RATCHET_PRIVS = {AEK2_PRIV, R1_PRIV, R2_PRIV, R3_PRIV};
    private static final String[] RATCHET_PUBS = {AEK2_PUB, R1_PUB, R2_PUB, R3_PUB};

    private RatchetVectors() {
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "ratchet-vectors.json";

        ArrayDeque<KeyPair> ratchetKeys = new ArrayDeque<>();
        for (int i = 0; i < RATCHET_PRIVS.length; i++) {
            ratchetKeys.add(new KeyPair(
                DhKeyUtil.decodePublicKey(b64(hex(RATCHET_PUBS[i]))),
                DhKeyUtil.decodePrivateKey(hex(RATCHET_PRIVS[i]))));
        }
        ArrayDeque<byte[]> ivs = new ArrayDeque<>();
        for (int i = 0; i < 4; i++) {
            byte[] iv = new byte[12];
            Arrays.fill(iv, (byte) (0x10 + i));
            ivs.add(iv);
        }

        RatchetService ratchet = new RatchetService();
        ratchet.setKeyPairSupplier(ratchetKeys::poll);
        ratchet.setRandomSupplier(length -> ivs.poll());

        PrivateKey aIk = DhKeyUtil.decodePrivateKey(hex(AIK_PRIV));
        PrivateKey bIk = DhKeyUtil.decodePrivateKey(hex(BIK_PRIV));
        PrivateKey bSpk = DhKeyUtil.decodePrivateKey(hex(BSPK_PRIV));
        PrivateKey bOpk = DhKeyUtil.decodePrivateKey(hex(BOPK_PRIV));

        // ---------- X3DH 握手 ----------
        RatchetService.InitiatorResult aResult = ratchet.initiatorSession(
            "bob", aIk, b64(hex(AIK_PUB)), b64(hex(BIK_PUB)),
            b64(hex(BSPK_PUB)), b64(hex(BOPK_PUB)));
        SessionState a = aResult.getState();

        SessionState b = ratchet.responderSession(
            "alice", bIk, b64(hex(BIK_PUB)), bSpk, b64(hex(BSPK_PUB)), bOpk,
            b64(hex(AIK_PUB)), aResult.getSenderEphemeralKey(), true);

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"handshake\": {");
        json.append("\n    \"a_identity_priv\": \"").append(AIK_PRIV).append("\",");
        json.append("\n    \"a_identity_pub\": \"").append(AIK_PUB).append("\",");
        json.append("\n    \"a_ephemeral_priv\": \"").append(AEK2_PRIV).append("\",");
        json.append("\n    \"a_ephemeral_pub\": \"").append(AEK2_PUB).append("\",");
        json.append("\n    \"b_identity_priv\": \"").append(BIK_PRIV).append("\",");
        json.append("\n    \"b_identity_pub\": \"").append(BIK_PUB).append("\",");
        json.append("\n    \"b_signed_pre_key_priv\": \"").append(BSPK_PRIV).append("\",");
        json.append("\n    \"b_signed_pre_key_pub\": \"").append(BSPK_PUB).append("\",");
        json.append("\n    \"b_one_time_pre_key_priv\": \"").append(BOPK_PRIV).append("\",");
        json.append("\n    \"b_one_time_pre_key_pub\": \"").append(BOPK_PUB).append("\",");
        json.append("\n    \"a_state\": ").append(state(a)).append(",");
        json.append("\n    \"b_state\": ").append(state(b)).append("\n  },");
        json.append("\n  \"steps\": [\n");

        // 1) A 发 m0
        RatchetService.Encrypted m0 = ratchet.encrypt(a, "m0");
        json.append("    ").append(encryptStep("A", "m0", m0, a));

        // 2) B 解 m0（首条消息触发 DH 棘轮）
        SessionState bBefore = b.copy();
        String p0 = ratchet.decrypt(b, m0.getHeader(), m0.getIv(), m0.getCiphertext());
        json.append(",\n    ").append(decryptStep("B", m0, p0, bBefore, b));

        // 3) B 回 r1
        RatchetService.Encrypted r1 = ratchet.encrypt(b, "r1");
        json.append(",\n    ").append(encryptStep("B", "r1", r1, b));

        // 4) A 解 r1（触发 DH 棘轮）
        SessionState aBefore = a.copy();
        String p1 = ratchet.decrypt(a, r1.getHeader(), r1.getIv(), r1.getCiphertext());
        json.append(",\n    ").append(decryptStep("A", r1, p1, aBefore, a));

        // 5) A 连发 m2/m3，B 先解 m3（跳键），再取 m2（缓存）
        RatchetService.Encrypted m2 = ratchet.encrypt(a, "m2");
        json.append(",\n    ").append(encryptStep("A", "m2", m2, a));
        RatchetService.Encrypted m3 = ratchet.encrypt(a, "m3");
        json.append(",\n    ").append(encryptStep("A", "m3", m3, a));

        SessionState bBeforeM3 = b.copy();
        String p3 = ratchet.decrypt(b, m3.getHeader(), m3.getIv(), m3.getCiphertext());
        json.append(",\n    ").append(decryptStep("B", m3, p3, bBeforeM3, b));
        SessionState bBeforeM2 = b.copy();
        String p2 = ratchet.decrypt(b, m2.getHeader(), m2.getIv(), m2.getCiphertext());
        json.append(",\n    ").append(decryptStep("B", m2, p2, bBeforeM2, b));

        json.append("\n  ]\n}\n");
        Files.write(Paths.get(out), json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
    }

    private static String encryptStep(String actor, String plaintext,
        RatchetService.Encrypted encrypted, SessionState after) {
        return "{\"actor\":\"" + actor + "\",\"op\":\"encrypt\",\"plaintext\":\""
            + hex(plaintext.getBytes(StandardCharsets.UTF_8))
            + "\",\"iv\":\"" + hex(Base64.getDecoder().decode(encrypted.getIv()))
            + "\",\"ciphertext\":\"" + hex(Base64.getDecoder().decode(encrypted.getCiphertext()))
            + "\",\"header\":" + header(encrypted)
            + ",\"state\":" + state(after) + "}";
    }

    private static String decryptStep(String actor, RatchetService.Encrypted encrypted,
        String plaintext, SessionState before, SessionState after) {
        String nextPriv = null;
        String nextPub = null;
        if (!before.getDhSelfPrivate().equals(after.getDhSelfPrivate())) {
            nextPriv = hex(Base64.getDecoder().decode(after.getDhSelfPrivate()));
            nextPub = hex(Base64.getDecoder().decode(after.getDhSelfPublic()));
        }
        return "{\"actor\":\"" + actor + "\",\"op\":\"decrypt\",\"plaintext\":\""
            + hex(plaintext.getBytes(StandardCharsets.UTF_8))
            + "\",\"iv\":\"" + hex(Base64.getDecoder().decode(encrypted.getIv()))
            + "\",\"ciphertext\":\"" + hex(Base64.getDecoder().decode(encrypted.getCiphertext()))
            + "\",\"header\":" + header(encrypted)
            + ",\"next_dh_private\":" + (nextPriv == null ? "null" : "\"" + nextPriv + "\"")
            + ",\"next_dh_public\":" + (nextPub == null ? "null" : "\"" + nextPub + "\"")
            + ",\"state\":" + state(after) + "}";
    }

    private static String header(RatchetService.Encrypted encrypted) {
        return "{\"dh\":\"" + encrypted.getHeader().getDhPublic()
            + "\",\"pn\":" + encrypted.getHeader().getPreviousChainLength()
            + ",\"n\":" + encrypted.getHeader().getMessageNumber() + "}";
    }

    private static String state(SessionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"root\":\"").append(hex(state.getRootKey()))
            .append("\",\"send\":").append(hexOrNull(state.getSendChainKey()))
            .append(",\"recv\":").append(hexOrNull(state.getRecvChainKey()))
            .append(",\"dh_self_private\":\"")
            .append(hex(Base64.getDecoder().decode(state.getDhSelfPrivate())))
            .append("\",\"dh_self_public\":\"")
            .append(hex(Base64.getDecoder().decode(state.getDhSelfPublic())))
            .append("\",\"dh_remote_public\":")
            .append(state.getDhRemotePublic() == null ? "null"
                : "\"" + hex(Base64.getDecoder().decode(state.getDhRemotePublic())) + "\"")
            .append(",\"send_count\":").append(state.getSendCount())
            .append(",\"recv_count\":").append(state.getRecvCount())
            .append(",\"prev_send_count\":").append(state.getPrevSendCount())
            .append(",\"skipped\":[");
        boolean first = true;
        if (state.getSkippedKeys() != null) {
            for (Map.Entry<String, String> entry : state.getSkippedKeys().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                String key = entry.getKey();
                int sep = key.lastIndexOf(':');
                sb.append("{\"dh\":\"").append(key.substring(0, sep))
                    .append("\",\"n\":").append(key.substring(sep + 1))
                    .append(",\"key\":\"")
                    .append(hex(Base64.getDecoder().decode(entry.getValue())))
                    .append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String hexOrNull(byte[] value) {
        return value == null ? "null" : "\"" + hex(value) + "\"";
    }

    private static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
