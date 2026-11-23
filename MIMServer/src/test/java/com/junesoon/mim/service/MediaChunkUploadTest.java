package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.entity.MediaUpload;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.MediaMapper;
import com.junesoon.mim.mapper.MediaUploadMapper;
import com.junesoon.mim.service.impl.MediaServiceImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 分片上传：顺序、长度、幂等与半成品清理。
 *
 * <p>大文件不能走单请求（会撑爆内存与网关），但分片带来一类新的错误：
 * 少一片、长短不对、重复上传把已完成的进度弄坏。这里逐条锁住。
 */
class MediaChunkUploadTest {

    /** 服务端下限是 64KB，这里就用它，避免用例被「分片太小」挡在门口。 */
    private static final int CHUNK = 64 * 1024;

    @TempDir
    Path dir;

    private MemoryMedia media;
    private MemoryUploads uploads;
    private MediaServiceImpl service;

    @BeforeEach
    void setUp() {
        media = new MemoryMedia();
        uploads = new MemoryUploads();
        service = new MediaServiceImpl(media, uploads, dir.toString(), 8L * 1024 * 1024,
            160L * 1024 * 1024);
    }

    @Test
    @DisplayName("分片齐全后按顺序合并，内容逐字节一致")
    void completesInOrder() throws IOException {
        byte[] content = payload(2 * CHUNK + 500);
        String uploadId = service.initUpload("alice", content.length, CHUNK);

        // 故意乱序上传：合并必须按 index，而不是按到达顺序。
        // putChunk 返回「已收到的片数」：乱序上传时它应该单调增长。
        assertEquals(1, service.putChunk("alice", uploadId, 1, slice(content, 1)));
        assertEquals(2, service.putChunk("alice", uploadId, 0, slice(content, 0)));
        assertEquals(3, service.putChunk("alice", uploadId, 2, slice(content, 2)));
        assertEquals(List.of(0, 1, 2), service.uploadStatus("alice", uploadId).received());

        MediaObject object = service.completeUpload("alice", uploadId);

        assertEquals(content.length, object.getSize());
        assertArrayEquals(content, Files.readAllBytes(dir.resolve(object.getId())));
        assertTrue(media.rows.containsKey(object.getId()), "合并后应落 t_media 记录");
        assertNull(uploads.select(uploadId), "进度记录应随完成一起清掉");
        assertFalse(Files.exists(dir.resolve(".parts").resolve(uploadId)), "分片目录应清空");
    }

    @Test
    @DisplayName("缺片不能合并，也不会留下半张媒体")
    void missingChunkRejected() {
        byte[] content = payload(2 * CHUNK + 500);
        String uploadId = service.initUpload("alice", content.length, CHUNK);
        service.putChunk("alice", uploadId, 0, slice(content, 0));
        service.putChunk("alice", uploadId, 2, slice(content, 2));

        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.completeUpload("alice", uploadId));

        assertTrue(error.getMessage().contains("第 1 片"), error.getMessage());
        assertTrue(media.rows.isEmpty(), "合片失败不应产生媒体记录");
    }

    @Test
    @DisplayName("分片长度与下标都要校验，重复上传同一片覆盖")
    void chunkValidation() {
        byte[] content = payload(2 * CHUNK + 500);
        String uploadId = service.initUpload("alice", content.length, CHUNK);

        assertThrows(UserInvalidException.class,
            () -> service.putChunk("alice", uploadId, 0, new byte[CHUNK - 1]));
        assertThrows(UserInvalidException.class,
            () -> service.putChunk("alice", uploadId, 3, slice(content, 0)));
        assertThrows(UserInvalidException.class,
            () -> service.putChunk("bob", uploadId, 0, slice(content, 0)));

        assertEquals(1, service.putChunk("alice", uploadId, 0, slice(content, 0)));
        assertEquals(1, service.putChunk("alice", uploadId, 0, slice(content, 0)));
    }

    @Test
    @DisplayName("参数与并发上限：过大、分片过小/过大、并发太多都拒绝")
    void initValidation() {
        assertThrows(UserInvalidException.class, () -> service.initUpload("alice", 0, CHUNK));
        assertThrows(UserInvalidException.class,
            () -> service.initUpload("alice", 200L * 1024 * 1024, CHUNK));
        assertThrows(UserInvalidException.class, () -> service.initUpload("alice", 4096, 1024));
        assertThrows(UserInvalidException.class,
            () -> service.initUpload("alice", 4096, 16 * 1024 * 1024));

        for (int i = 0; i < 5; i++) {
            service.initUpload("alice", 4096, CHUNK);
        }
        assertThrows(UserInvalidException.class, () -> service.initUpload("alice", 4096, CHUNK));
    }

    @Test
    @DisplayName("取消删除分片；巡检只清过期的半成品")
    void abortAndPurge() {
        String live = service.initUpload("alice", 2 * CHUNK + 500, CHUNK);
        String stale = service.initUpload("alice", 2 * CHUNK + 500, CHUNK);
        service.putChunk("alice", live, 0, payload(CHUNK));
        uploads.rows.get(stale).setUpdatedAt(1_000L);

        assertTrue(service.abortUpload("alice", live));
        assertFalse(Files.exists(dir.resolve(".parts").resolve(live)));
        assertFalse(service.abortUpload("alice", live), "已取消的任务再取消返回 false");
        assertFalse(service.abortUpload("bob", stale), "别人的任务不能取消");

        int purged = service.purgeStaleUploads(5_000L, 100);

        assertEquals(1, purged);
        assertFalse(Files.exists(dir.resolve(".parts").resolve(stale)));
        assertNull(uploads.select(stale), "过期半成品的进度记录也要清掉");
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static byte[] slice(byte[] content, int index) {
        int from = index * CHUNK;
        int to = Math.min(from + CHUNK, content.length);
        byte[] out = new byte[to - from];
        System.arraycopy(content, from, out, 0, out.length);
        return out;
    }

    private static final class MemoryMedia implements MediaMapper {

        private final Map<String, MediaObject> rows = new HashMap<>();

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
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public List<String> selectIdsOlderThan(long cutoff, int limit) {
            return List.of();
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }
    }

    private static final class MemoryUploads implements MediaUploadMapper {

        private final Map<String, MediaUpload> rows = new HashMap<>();

        @Override
        public int insert(MediaUpload upload) {
            rows.put(upload.getUploadId(), upload);
            return 1;
        }

        @Override
        public MediaUpload select(String uploadId) {
            return rows.get(uploadId);
        }

        @Override
        public int countByOwner(String owner) {
            return (int) rows.values().stream().filter(u -> u.getOwner().equals(owner)).count();
        }

        @Override
        public List<MediaUpload> selectStale(long cutoff, int limit) {
            List<MediaUpload> stale = new ArrayList<>();
            rows.values().stream()
                .filter(u -> u.getUpdatedAt() < cutoff)
                .sorted(Comparator.comparingLong(MediaUpload::getUpdatedAt))
                .limit(limit)
                .forEach(stale::add);
            return stale;
        }

        @Override
        public int touch(String uploadId, long updatedAt) {
            MediaUpload upload = rows.get(uploadId);
            if (upload == null) {
                return 0;
            }
            upload.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public int delete(String uploadId) {
            return rows.remove(uploadId) == null ? 0 : 1;
        }
    }
}
