package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Rust ⇄ Java 互通联调的 Java 侧。
 *
 * <p>读取 Rust 发起方写下的握手参数与首条消息，用 {@link RatchetService}
 * 的接收方路径解密，再加密一条回信交给 Rust 解密。
 *
 * <p>放在 `MIMCore/tools`，不参与 Maven 构建；由 `MIMCoreRust/tools/interop.sh`
 * 用 javac 编译到临时目录。之所以放在 {@code com.junesoon.mim.crypto} 包内，
 * 是为了能注入固定 IV（包内可见的测试注入点）。
 */
public final class InteropResponder {

    private static final String B_IDENTITY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104206a3364f2700f5aab8d9e81f56c3776a167fcd66f2c62299274afb64e9ad531dea14403420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String B_IDENTITY_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String B_SIGNED_PRE_KEY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420938517594ecaf3587286695e0e675f1177c0278b6281f3517c83ace4ab8bf0e6a14403420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String B_SIGNED_PRE_KEY_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String B_ONE_TIME_PRE_KEY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420c998cc833a3669a48ad6de76655b06aa568dc152a73517675afe94da3bf444d5a144034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String B_ONE_TIME_PRE_KEY_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";

    private static final String EXPECTED_FIRST_MESSAGE = "hello from Rust";
    private static final String REPLY = "hello from Java";

    private InteropResponder() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "/tmp/mim-interop");
        Map<String, String> init = parseKv(new String(
            Files.readAllBytes(dir.resolve("a_init.txt")), StandardCharsets.UTF_8));

        PrivateKey bIk = DhKeyUtil.decodePrivateKey(unhex(B_IDENTITY_PRIV));
        PrivateKey bSpk = DhKeyUtil.decodePrivateKey(unhex(B_SIGNED_PRE_KEY_PRIV));
        PrivateKey bOpk = DhKeyUtil.decodePrivateKey(unhex(B_ONE_TIME_PRE_KEY_PRIV));

        RatchetService ratchet = new RatchetService();
        byte[] replyIv = new byte[12];
        java.util.Arrays.fill(replyIv, (byte) 0x30);
        ratchet.setRandomSupplier(length -> replyIv);

        SessionState state = ratchet.responderSession(
            "alice", bIk, b64(unhex(B_IDENTITY_PUB)), bSpk,
            b64(unhex(B_SIGNED_PRE_KEY_PUB)), bOpk,
            init.get("sender_identity"), init.get("sender_ephemeral"),
            "1".equals(init.get("used_opk")));

        RatchetService.Header header = new RatchetService.Header(
            init.get("dh"), Integer.parseInt(init.get("pn")), Integer.parseInt(init.get("n")));
        String plaintext = ratchet.decrypt(state, header, init.get("iv"), init.get("ciphertext"));
        if (!EXPECTED_FIRST_MESSAGE.equals(plaintext)) {
            throw new IllegalStateException("首条消息正文不符：" + plaintext);
        }
        System.out.println("java: 解密 Rust 首条消息 -> " + plaintext);

        RatchetService.Encrypted reply = ratchet.encrypt(state, REPLY);
        StringBuilder out = new StringBuilder();
        out.append("dh=").append(reply.getHeader().getDhPublic()).append('\n');
        out.append("pn=").append(reply.getHeader().getPreviousChainLength()).append('\n');
        out.append("n=").append(reply.getHeader().getMessageNumber()).append('\n');
        out.append("iv=").append(reply.getIv()).append('\n');
        out.append("ciphertext=").append(reply.getCiphertext()).append('\n');
        Files.write(dir.resolve("java_reply.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("java: 已写 java_reply.txt");
        System.out.println("INTEROP_JAVA_OK");
    }

    private static Map<String, String> parseKv(String text) {
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            int sep = line.indexOf('=');
            if (sep > 0) {
                fields.put(line.substring(0, sep), line.substring(sep + 1).trim());
            }
        }
        return fields;
    }

    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
