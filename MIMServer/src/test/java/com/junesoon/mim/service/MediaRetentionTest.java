package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.mapper.MediaMapper;
import com.junesoon.mim.service.impl.MediaServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 媒体保留期巡检。
 *
 * <p>服务端看不到密文里的媒体引用，只能按创建时间兜底清理；
 * 因此这里重点验证两件事：只删过期的，以及文件删不掉时不要留下孤儿记录。
 */
class MediaRetentionTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @TempDir
    Path dir;

    private FakeMediaMapper mapper;
    private MediaServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = new FakeMediaMapper();
        // 保留期用不到分片上传的映射，传一个空实现即可。
        service = new MediaServiceImpl(mapper, null, dir.toString(), 1024 * 1024,
            160L * 1024 * 1024);
    }

    @Test
    @DisplayName("只清理早于保留期的密文，新文件与记录都不动")
    void purgesOnlyExpired() {
        MediaObject old = service.save("alice", "old-content".getBytes());
        MediaObject fresh = service.save("alice", "fresh-content".getBytes());
        long now = System.currentTimeMillis();
        mapper.setCreatedAt(old.getId(), now - 40 * DAY_MS);

        int removed = service.purgeOlderThan(now - 30 * DAY_MS, 200);

        assertEquals(1, removed);
        assertFalse(Files.exists(dir.resolve(old.getId())), "过期密文应被删除");
        assertTrue(Files.exists(dir.resolve(fresh.getId())), "未过期密文必须保留");
        assertTrue(mapper.ids().contains(fresh.getId()));
        assertFalse(mapper.ids().contains(old.getId()));
    }

    @Test
    @DisplayName("文件删不掉时保留记录，下一轮再试，不制造孤儿文件")
    void keepsRowWhenFileCannotBeRemoved() throws Exception {
        // 用一个非空目录顶替文件：deleteIfExists 会抛 DirectoryNotEmptyException。
        Path stubborn = dir.resolve("stubborn-file-dir");
        Files.createDirectories(stubborn.resolve("inner"));
        MediaObject row = new MediaObject();
        row.setId("stubborn-file-dir");
        row.setOwner("alice");
        row.setSize(1);
        row.setCrtTime(System.currentTimeMillis() - 40 * DAY_MS);
        mapper.put(row);

        int removed = service.purgeOlderThan(System.currentTimeMillis() - 30 * DAY_MS, 200);

        assertEquals(0, removed);
        assertTrue(mapper.ids().contains("stubborn-file-dir"), "删不掉文件时不能先删库记录");
        assertTrue(Files.exists(stubborn), "文件应保持原样");
    }

    @Test
    @DisplayName("记录在、文件已丢失时按清理处理，清掉这条不一致记录")
    void removesDanglingRow() {
        MediaObject row = new MediaObject();
        row.setId("missing-blob-000000000000");
        row.setOwner("alice");
        row.setSize(0);
        row.setCrtTime(System.currentTimeMillis() - 40 * DAY_MS);
        mapper.put(row);

        int removed = service.purgeOlderThan(System.currentTimeMillis() - 30 * DAY_MS, 200);

        assertEquals(1, removed);
        assertFalse(mapper.ids().contains("missing-blob-000000000000"));
    }

    private static final class FakeMediaMapper implements MediaMapper {

        private final Map<String, MediaObject> rows = new LinkedHashMap<>();

        void put(MediaObject object) {
            rows.put(object.getId(), object);
        }

        void setCreatedAt(String id, long createdAt) {
            rows.get(id).setCrtTime(createdAt);
        }

        List<String> ids() {
            return new ArrayList<>(rows.keySet());
        }

        @Override
        public int insert(MediaObject object) {
            rows.put(object.getId(), object);
            return 1;
        }

        @Override
        public MediaObject selectById(String id) {
            return rows.get(id);
        }

        @Override
        public int deleteByIdAndOwner(String id, String owner) {
            MediaObject row = rows.get(id);
            if (row == null || !row.getOwner().equals(owner)) {
                return 0;
            }
            rows.remove(id);
            return 1;
        }

        @Override
        public List<String> selectIdsOlderThan(long cutoff, int limit) {
            List<String> result = new ArrayList<>();
            for (MediaObject row : rows.values()) {
                if (row.getCrtTime() < cutoff && result.size() < limit) {
                    result.add(row.getId());
                }
            }
            return result;
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }
    }
}
