package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafetyNumberTest {

    private static String key(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return Base64.getEncoder().encodeToString(data);
    }

    @Test
    @DisplayName("双方交换公钥后得到同一个 60 位安全码")
    void symmetric() {
        String alice = key(1, 2, 3, 4);
        String bob = key(9, 8, 7);
        String first = SafetyNumber.of(alice, bob);
        String second = SafetyNumber.of(bob, alice);
        assertEquals(first, second);
        assertEquals(60, first.replaceAll("[^0-9]", "").length());
    }

    @Test
    @DisplayName("不同设备公钥产生不同安全码")
    void differentKeys() {
        String alice = key(1, 2, 3, 4);
        String bob = key(9, 8, 7);
        String other = key(9, 8, 6);
        assertNotEquals(SafetyNumber.of(alice, bob), SafetyNumber.of(alice, other));
    }
}
