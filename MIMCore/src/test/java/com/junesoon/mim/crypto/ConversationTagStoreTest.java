package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话标签的本地存储测试。
 *
 * <p>标签是服务端按它查历史的唯一依据，必须与明文一起加密落盘、
 * 重启后可读，且同一对端可以保存多个（对端每台设备各自生成过）。
 *
 * [INPUT]: 只依赖 PlaintextStore 与临时目录
 * [OUTPUT]: 验证按对端读写、去重、上限与重启持久化
 * [POS]: 会话标签在客户端的落地行为
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class ConversationTagStoreTest {

    @Test
    @DisplayName("按对端保存标签，重启后仍可读")
    void tagsSurviveReload(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "pw");
        store.addConversationTag("bob", "tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        store.addConversationTag("carol", "tagCCCCCCCCCCCCCCCCCCCCCCCC2");

        PlaintextStore reloaded = new PlaintextStore(dir);
        reloaded.load("alice", "pw");
        assertEquals(1, reloaded.getConversationTags("bob").size());
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", reloaded.getConversationTags("bob").get(0));
        assertEquals("tagCCCCCCCCCCCCCCCCCCCCCCCC2",
            reloaded.getConversationTags("carol").get(0));
        assertTrue(reloaded.getConversationTags("dave").isEmpty());
    }

    @Test
    @DisplayName("同一对端可保存多个标签，重复添加不重复计数")
    void multipleTagsPerPeer(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "pw");
        store.addConversationTag("bob", "tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        store.addConversationTag("bob", "tagBBBBBBBBBBBBBBBBBBBBBBBB2");
        store.addConversationTag("bob", "tagAAAAAAAAAAAAAAAAAAAAAAAA1");

        assertEquals(2, store.getConversationTags("bob").size());
    }

    @Test
    @DisplayName("标签数量有上限，超出不会无限增长")
    void tagCountCapped(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "pw");
        for (int i = 0; i < 30; i++) {
            store.addConversationTag("bob", String.format("tag%022d", i));
        }
        assertEquals(20, store.getConversationTags("bob").size());
    }
}
