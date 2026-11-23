package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.entity.MediaUpload;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.MediaMapper;
import com.junesoon.mim.mapper.MediaUploadMapper;
import com.junesoon.mim.service.MediaService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加密媒体落盘实现。
 *
 * <p>写入用「临时文件 + 原子移动」，避免下载到写了一半的文件。
 * 记录落库失败时删掉文件，磁盘与数据库不会各留一半。
 */
@Slf4j
@Service
public class MediaServiceImpl implements MediaService {

    private static final int ID_BYTES = 32;

    /** 分片大小下限 / 上限：太小请求次数爆炸，太大又回到「一个请求撑爆内存」。 */
    private static final int MIN_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;

    /** 单次上传最多多少片：防止有人用小分片刷出几万个请求。 */
    private static final int MAX_CHUNKS = 4000;

    /** 同一用户同时进行的未完成上传上限。 */
    private static final int MAX_PENDING_UPLOADS = 5;

    /** 未完成分片的目录名（带点前缀，不会与媒体 id 冲突）。 */
    private static final String PARTS_DIR = ".parts";

    private final MediaMapper mediaMapper;
    private final MediaUploadMapper uploadMapper;
    private final Path root;
    private final Path partsRoot;
    private final long maxBytes;
    private final long maxUploadBytes;
    private final SecureRandom random = new SecureRandom();

    public MediaServiceImpl(
        MediaMapper mediaMapper,
        MediaUploadMapper uploadMapper,
        @Value("${mim.media.dir:data/media}") String dir,
        @Value("${mim.media.max-bytes:" + (8 * 1024 * 1024) + "}") long maxBytes,
        @Value("${mim.media.max-upload-bytes:" + (160L * 1024 * 1024) + "}") long maxUploadBytes) {
        this.mediaMapper = mediaMapper;
        this.uploadMapper = uploadMapper;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.partsRoot = root.resolve(PARTS_DIR);
        this.maxBytes = maxBytes;
        this.maxUploadBytes = maxUploadBytes;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建媒体目录：" + root, e);
        }
        try {
            Files.createDirectories(partsRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建分片目录：" + partsRoot, e);
        }
        log.info("加密媒体目录：{}（单片上限 {} 字节，分片上传总上限 {} 字节）",
            root, maxBytes, maxUploadBytes);
    }

    @Override
    public MediaObject save(String owner, byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            throw new UserInvalidException("媒体内容为空");
        }
        if (encrypted.length > maxBytes) {
            throw new UserInvalidException("媒体文件过大，上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String id = newId();
        Path target = root.resolve(id);
        Path temp = root.resolve(id + ".part");
        try {
            Files.write(temp, encrypted);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("媒体写入失败", e);
        }

        MediaObject object = new MediaObject();
        object.setId(id);
        object.setOwner(owner);
        object.setSize(encrypted.length);
        object.setCrtTime(System.currentTimeMillis());
        try {
            mediaMapper.insert(object);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return object;
    }

    @Override
    public Path load(String id) {
        MediaObject object = mediaMapper.selectById(id);
        if (object == null) {
            return null;
        }
        Path path = root.resolve(object.getId());
        return Files.isRegularFile(path) ? path : null;
    }

    @Override
    public MediaObject metadata(String id) {
        return mediaMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String owner, String id) {
        int affected = mediaMapper.deleteByIdAndOwner(id, owner);
        if (affected == 0) {
            return false;
        }
        deleteQuietly(root.resolve(id));
        return true;
    }

    @Override
    public int purgeOlderThan(long cutoffMillis, int batchSize) {
        int size = Math.max(1, batchSize);
        int removed = 0;
        while (true) {
            List<String> ids = mediaMapper.selectIdsOlderThan(cutoffMillis, size);
            if (ids.isEmpty()) {
                break;
            }
            int deletedThisRound = 0;
            for (String id : ids) {
                if (!removeFile(id)) {
                    // 文件删不掉就留着这行，下一轮再试；不制造孤儿文件。
                    continue;
                }
                mediaMapper.deleteById(id);
                deletedThisRound++;
            }
            removed += deletedThisRound;
            // 取回不足一批说明已经到底；整批都删不掉时退出，避免死循环。
            if (ids.size() < size || deletedThisRound == 0) {
                break;
            }
        }
        if (removed > 0) {
            log.info("媒体保留期清理：删除 {} 个早于 {} 的密文", removed, cutoffMillis);
        }
        return removed;
    }

    @Override
    public String initUpload(String owner, long totalSize, int chunkSize) {
        if (totalSize <= 0 || totalSize > maxUploadBytes) {
            throw new UserInvalidException(
                "文件过大，上限 " + (maxUploadBytes / 1024 / 1024) + "MB");
        }
        if (chunkSize < MIN_CHUNK_BYTES || chunkSize > MAX_CHUNK_BYTES) {
            throw new UserInvalidException("分片大小不合法");
        }
        if ((totalSize + chunkSize - 1) / chunkSize > MAX_CHUNKS) {
            throw new UserInvalidException("分片数量过多，请调大分片");
        }
        if (uploadMapper.countByOwner(owner) >= MAX_PENDING_UPLOADS) {
            throw new UserInvalidException("同时进行的上传过多，请等完成或取消后再试");
        }
        String uploadId = newId();
        Path dir = partsRoot.resolve(uploadId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建分片目录", e);
        }
        long now = System.currentTimeMillis();
        MediaUpload upload = new MediaUpload();
        upload.setUploadId(uploadId);
        upload.setOwner(owner);
        upload.setTotalSize(totalSize);
        upload.setChunkSize(chunkSize);
        upload.setCreatedAt(now);
        upload.setUpdatedAt(now);
        try {
            uploadMapper.insert(upload);
        } catch (RuntimeException e) {
            deleteRecursively(dir);
            throw e;
        }
        return uploadId;
    }

    @Override
    public int putChunk(String owner, String uploadId, int index, byte[] chunk) {
        MediaUpload upload = requireOwned(owner, uploadId);
        int total = totalChunks(upload);
        if (index < 0 || index >= total) {
            throw new UserInvalidException("分片下标越界");
        }
        long expected = expectedChunkSize(upload, index);
        if (chunk == null || chunk.length != expected) {
            throw new UserInvalidException("分片长度不符：期望 " + expected + " 字节，实际 "
                + (chunk == null ? 0 : chunk.length));
        }
        Path dir = partsRoot.resolve(uploadId);
        Path target = dir.resolve(String.valueOf(index));
        Path temp = dir.resolve(index + ".part");
        try {
            Files.createDirectories(dir);
            Files.write(temp, chunk);
            // 同一片重复上传直接覆盖：幂等，重传不会把已完成的进度弄坏。
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("分片写入失败", e);
        }
        uploadMapper.touch(uploadId, System.currentTimeMillis());
        return receivedIndices(uploadId).size();
    }

    @Override
    public UploadStatus uploadStatus(String owner, String uploadId) {
        MediaUpload upload = requireOwned(owner, uploadId);
        return new UploadStatus(upload.getChunkSize(), totalChunks(upload),
            receivedIndices(uploadId));
    }

    @Override
    public MediaObject completeUpload(String owner, String uploadId) {
        MediaUpload upload = requireOwned(owner, uploadId);
        int total = totalChunks(upload);
        Path dir = partsRoot.resolve(uploadId);
        String mediaId = newId();
        Path target = root.resolve(mediaId);
        Path temp = root.resolve(mediaId + ".part");
        try (OutputStream out = Files.newOutputStream(temp)) {
            for (int index = 0; index < total; index++) {
                Path part = dir.resolve(String.valueOf(index));
                if (!Files.exists(part) || Files.size(part) != expectedChunkSize(upload, index)) {
                    deleteQuietly(temp);
                    throw new UserInvalidException("第 " + index + " 片缺失或长度不符，请重传该分片");
                }
                Files.copy(part, out);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("合并分片失败", e);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("媒体写入失败", e);
        }

        MediaObject object = new MediaObject();
        object.setId(mediaId);
        object.setOwner(owner);
        object.setSize(upload.getTotalSize());
        object.setCrtTime(System.currentTimeMillis());
        try {
            mediaMapper.insert(object);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        // 合并成功即回收分片与进度；失败也只是留下半成品，巡检会清。
        deleteRecursively(dir);
        uploadMapper.delete(uploadId);
        log.info("分片上传完成：{} 片，{} 字节，uploadId={}", total, upload.getTotalSize(), uploadId);
        return object;
    }

    @Override
    public boolean abortUpload(String owner, String uploadId) {
        MediaUpload upload = uploadMapper.select(uploadId);
        if (upload == null) {
            return false;
        }
        if (!upload.getOwner().equals(owner)) {
            // 不泄露「这个 uploadId 存在但不是你的」。
            return false;
        }
        deleteRecursively(partsRoot.resolve(uploadId));
        uploadMapper.delete(uploadId);
        return true;
    }

    @Override
    public int purgeStaleUploads(long cutoffMillis, int batchSize) {
        int size = Math.max(1, batchSize);
        List<MediaUpload> stale = uploadMapper.selectStale(cutoffMillis, size);
        int removed = 0;
        for (MediaUpload upload : stale) {
            deleteRecursively(partsRoot.resolve(upload.getUploadId()));
            uploadMapper.delete(upload.getUploadId());
            removed++;
        }
        if (removed > 0) {
            log.info("分片上传巡检：清理 {} 个超过保留期未完成的上传", removed);
        }
        return removed;
    }

    /** 取上传记录并校验归属；不属于本人时按「不存在」处理，避免探测。 */
    private MediaUpload requireOwned(String owner, String uploadId) {
        MediaUpload upload = uploadMapper.select(uploadId);
        if (upload == null || !upload.getOwner().equals(owner)) {
            throw new UserInvalidException("上传任务不存在或已过期");
        }
        return upload;
    }

    private static int totalChunks(MediaUpload upload) {
        return (int) ((upload.getTotalSize() + upload.getChunkSize() - 1) / upload.getChunkSize());
    }

    /** 最后一片通常短于 chunkSize，按总大小精确算。 */
    private static long expectedChunkSize(MediaUpload upload, int index) {
        long offset = (long) index * upload.getChunkSize();
        return Math.min(upload.getChunkSize(), upload.getTotalSize() - offset);
    }

    /** 已落盘的分片下标（升序）；目录不存在视为空。 */
    private List<Integer> receivedIndices(String uploadId) {
        Path dir = partsRoot.resolve(uploadId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Integer> indices = new ArrayList<>();
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.endsWith(".part")) {
                    return;
                }
                try {
                    indices.add(Integer.parseInt(name));
                } catch (NumberFormatException ignored) {
                    // 非分片文件（例如临时文件）忽略
                }
            });
            indices.sort(Comparator.naturalOrder());
            return indices;
        } catch (IOException e) {
            log.warn("读取分片目录失败：{}（{}）", uploadId, e.getMessage());
            return List.of();
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("清理分片失败：{}（{}）", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("遍历分片目录失败：{}（{}）", dir, e.getMessage());
        }
    }

    /** 文件不存在视为已删除成功；IO 失败返回 false。 */
    private boolean removeFile(String id) {
        try {
            Files.deleteIfExists(root.resolve(id));
            return true;
        } catch (IOException e) {
            log.warn("媒体密文删除失败，保留记录待下轮重试：{}（{}）", id, e.getMessage());
            return false;
        }
    }

    private String newId() {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程：下次巡检可以再删
        }
    }
}
