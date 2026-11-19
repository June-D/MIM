package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地屏蔽名单测试。
 *
 * <p>屏蔽必须与明文一起加密落盘；重启后仍生效，解除后立即消失。
 *
 * [INPUT]: 只依赖 PlaintextStore 与临时目录
 * [OUTPUT]: 验证屏蔽/解除/重启持久化与列表
 * [POS]: 匿名消息下唯一可用的滥用拦截手段
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class BlockedPeerStoreTest {

    @Test
    @DisplayName("屏蔽后重启仍生效，解除后消失")
    void blockSurvivesReload(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "pw");
        assertFalse(store.isBlocked("bob"));

        store.block("bob");
        assertTrue(store.isBlocked("bob"));
        assertFalse(store.isBlocked("carol"));

        PlaintextStore reloaded = new PlaintextStore(dir);
        reloaded.load("alice", "pw");
        assertTrue(reloaded.isBlocked("bob"), "屏蔽状态必须持久化");

        List<String> blocked = reloaded.blockedPeers();
        assertTrue(blocked.contains("bob"));
        assertFalse(blocked.contains("carol"));

        reloaded.unblock("bob");
        assertFalse(reloaded.isBlocked("bob"));
    }
}
