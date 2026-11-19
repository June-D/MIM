package com.junesoon.mim.core;

import java.nio.charset.StandardCharsets;

/**
 * JNI 绑定的冒烟测试：A 建会话 → 加密 → B 解密 → 回信 → A 解密，
 * 再验证状态保存/恢复后继续解密。
 *
 * <p>运行：{@code java -Djava.library.path=MIMCoreRust/target/release
 * -cp <classes> com.junesoon.mim.core.MimCoreNativeTest}
 *
 * <p>这就是 Android 侧调用 Rust 核心的最小样例（换成 Android 的
 * {@code System.loadLibrary} 路径即可）。
 */
public final class MimCoreNativeTest {

    private static final String A_IDENTITY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed1aaac79c7a0a07e89016ca9fd088faecb674bfdb364c61772b304dc47656b0a14403420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String A_IDENTITY_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
    private static final String A_EPHEMERAL_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420db9918aa00a325c358939f01c7657587656f46c8bf97f05d84a396ca933f3df0a144034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
    private static final String A_EPHEMERAL_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
    private static final String B_IDENTITY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104206a3364f2700f5aab8d9e81f56c3776a167fcd66f2c62299274afb64e9ad531dea14403420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String B_IDENTITY_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
    private static final String B_SIGNED_PRE_KEY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420938517594ecaf3587286695e0e675f1177c0278b6281f3517c83ace4ab8bf0e6a14403420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String B_SIGNED_PRE_KEY_PUB = "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
    private static final String B_ONE_TIME_PRE_KEY_PRIV = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420c998cc833a3669a48ad6de76655b06aa568dc152a73517675afe94da3bf444d5a144034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
    private static final String B_ONE_TIME_PRE_KEY_PUB = "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";

    private static final String SPK_SIGNATURE =
        "MEUCIQD4MX39ZTDckhzL6Ql1gSGQQjaUQzzcQ2ta/ILSLHoMOAIgSbLzDswGAXWGEodmtHBxzmGRP8JEamuGXG4Vl7piwqA=";

    private MimCoreNativeTest() {
    }

    public static void main(String[] args) {
        long a = MimCoreNative.initiatorSession(
            unhex(A_IDENTITY_PRIV), unhex(A_IDENTITY_PUB),
            unhex(B_IDENTITY_PUB), unhex(B_SIGNED_PRE_KEY_PUB), unhex(B_ONE_TIME_PRE_KEY_PUB),
            unhex(A_EPHEMERAL_PRIV), unhex(A_EPHEMERAL_PUB));
        System.out.println("A 建会话：" + a);

        byte[] keys = MimCoreNative.initiatorPubkeys(a);
        System.out.println("发起方参数：" + new String(keys, StandardCharsets.UTF_8));

        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 0x70);
        byte[] m0 = MimCoreNative.encrypt(a, iv, "hello from JNI".getBytes(StandardCharsets.UTF_8));
        System.out.println("首条消息：" + new String(m0, StandardCharsets.UTF_8));

        long b = MimCoreNative.responderSession(
            unhex(B_IDENTITY_PRIV), unhex(B_IDENTITY_PUB),
            unhex(B_SIGNED_PRE_KEY_PRIV), unhex(B_SIGNED_PRE_KEY_PUB), unhex(B_ONE_TIME_PRE_KEY_PRIV),
            unhex(A_IDENTITY_PUB), unhex(A_EPHEMERAL_PUB), true);
        System.out.println("B 建会话：" + b);

        String text = new String(MimCoreNative.decrypt(b, m0), StandardCharsets.UTF_8);
        expect(text, "hello from JNI", "B 解密首条消息");
        System.out.println("B 解密首条消息成功");

        boolean replayed = false;
        try {
            MimCoreNative.decrypt(b, m0);
        } catch (IllegalStateException e) {
            replayed = true;
        }
        if (!replayed) {
            throw new IllegalStateException("重复投递必须被拒绝");
        }
        System.out.println("重复投递被拒绝");

        java.util.Arrays.fill(iv, (byte) 0x71);
        byte[] reply = MimCoreNative.encrypt(b, iv, "reply from JNI".getBytes(StandardCharsets.UTF_8));
        text = new String(MimCoreNative.decrypt(a, reply), StandardCharsets.UTF_8);
        expect(text, "reply from JNI", "A 解密回信");
        System.out.println("A 解密回信成功");

        byte[] state = MimCoreNative.save(b);
        System.out.println("B 状态快照 " + state.length + " 字节");

        // 加密落盘：密钥由平台密钥库保管，这里用随机密钥演示。
        byte[] stateKey = MimCoreNative.randomBytes(32);
        byte[] stateNonce = MimCoreNative.randomBytes(12);
        byte[] sealed = MimCoreNative.encryptState(state, stateKey, stateNonce);
        System.out.println("加密后的状态 " + sealed.length + " 字节");
        byte[] opened = MimCoreNative.decryptState(sealed, stateKey);
        if (!java.util.Arrays.equals(opened, state)) {
            throw new IllegalStateException("状态加解密往返不一致");
        }
        byte[] wrongKey = stateKey.clone();
        wrongKey[0] ^= (byte) 0xff;
        boolean rejected = false;
        try {
            MimCoreNative.decryptState(sealed, wrongKey);
        } catch (IllegalStateException e) {
            rejected = true;
        }
        if (!rejected) {
            throw new IllegalStateException("错误密钥必须被拒绝");
        }
        System.out.println("状态加解密与错误密钥拒绝通过");

        MimCoreNative.free(b);
        b = MimCoreNative.load(state);

        java.util.Arrays.fill(iv, (byte) 0x72);
        byte[] third = MimCoreNative.encrypt(a, iv, "after restore".getBytes(StandardCharsets.UTF_8));
        text = new String(MimCoreNative.decrypt(b, third), StandardCharsets.UTF_8);
        expect(text, "after restore", "恢复状态后继续解密");
        System.out.println("恢复状态后继续解密成功");

        byte[] bundle = MimCoreNative.generateKeyBundle(1, 4);
        if (bundle.length < 100) {
            throw new IllegalStateException("密钥包过短");
        }
        System.out.println("密钥包 " + bundle.length + " 字节");

        byte[] sig = SPK_SIGNATURE.getBytes(StandardCharsets.UTF_8);
        if (!MimCoreNative.verifySignedPreKey(
            unhex(A_IDENTITY_PUB), 1, unhex(B_SIGNED_PRE_KEY_PUB), sig)) {
            throw new IllegalStateException("签名预密钥应验签通过");
        }
        if (MimCoreNative.verifySignedPreKey(
            unhex(A_IDENTITY_PUB), 2, unhex(B_SIGNED_PRE_KEY_PUB), sig)) {
            throw new IllegalStateException("篡改 ID 必须失败");
        }
        System.out.println("签名预密钥验签与篡改拒绝通过");

        MimCoreNative.free(a);
        MimCoreNative.free(b);
        System.out.println("JNI_OK");
    }

    private static void expect(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " 正文不符：" + actual);
        }
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
