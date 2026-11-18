package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.config.KeyConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MetadataRefTest {

    private static MetadataRef ref(String configuredKey, byte[] jwtPrivateKey) {
        MetadataRef ref = new MetadataRef();
        ReflectionTestUtils.setField(ref, "configuredKey", configuredKey);
        KeyConfiguration configuration = new KeyConfiguration();
        configuration.setUserPriKey(jwtPrivateKey);
        ReflectionTestUtils.setField(ref, "keyConfiguration", configuration);
        return ref;
    }

    @Test
    @DisplayName("盲引用稳定、区分用户与设备，且不包含用户名")
    void referencesAreStableAndOpaque() {
        byte[] key = new byte[32];
        new Random(7).nextBytes(key);
        MetadataRef ref = ref(Base64.getEncoder().encodeToString(key), null);
        assertTrue(ref.isEnabled());

        String alice = ref.userRef("alice");
        String bob = ref.userRef("bob");
        assertEquals(alice, ref.userRef("alice"));
        assertNotEquals(alice, bob);
        assertEquals(64, alice.length());
        assertNotEquals(ref.deviceRef("alice", "phone"),
            ref.deviceRef("alice", "laptop"));
    }

    @Test
    @DisplayName("未配置独立密钥时退化为 JWT 私钥派生")
    void fallsBackToJwtPrivateKey() {
        MetadataRef ref = ref(null, "jwt-private-key".getBytes(StandardCharsets.UTF_8));
        assertTrue(ref.isEnabled());
        assertEquals(ref.userRef("alice"), ref.userRef("alice"));
        assertNotEquals(ref.userRef("alice"), ref.userRef("bob"));
    }
}
