package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.Avatar;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.AvatarMapper;
import com.junesoon.mim.service.impl.AvatarServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 头像只接受 PNG、有大小上限、版本自增；删除要真的把文件也删掉。
 *
 * <p>只接受 PNG 是有意的：客户端上传前重编码成 PNG，EXIF（含 GPS）不会进服务器。
 */
class AvatarServiceTest {

    private static final byte[] PNG = png(2048);

    @TempDir
    Path dir;

    private MemoryAvatars mapper;
    private AvatarServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = new MemoryAvatars();
        service = new AvatarServiceImpl(mapper, dir.toString());
    }

    @Test
    @DisplayName("保存后可读回，版本号每次自增")
    void saveBumpsVersionAndIsReadable() {
        assertEquals(1, service.save("alice", PNG));
        assertEquals(2, service.save("alice", PNG));
        assertArrayEquals(PNG, service.load("alice"));
        assertEquals(2, service.metadata("alice").getVersion());
    }

    @Test
    @DisplayName("非 PNG 与超限都明确拒绝，且不落盘")
    void rejectsNonPngAndOversize() {
        assertThrows(UserInvalidException.class,
            () -> service.save("alice", "not a png".getBytes()));
        assertEquals(null, service.load("alice"));

        byte[] huge = png(AvatarService.MAX_BYTES + 1);
        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.save("alice", huge));
        assertTrue(error.getMessage().contains("KB"), error.getMessage());
        assertEquals(null, service.load("alice"));
    }

    @Test
    @DisplayName("非法用户名既不读也不写，防止拼出库外路径")
    void rejectsBadUsername() {
        assertThrows(UserInvalidException.class, () -> service.save("../../etc", PNG));
        assertNull(service.load("../../etc"));
        assertNull(service.metadata("../x"));
    }

    @Test
    @DisplayName("删除同时清掉文件与版本记录")
    void deleteRemovesFile() {
        service.save("alice", PNG);
        assertTrue(service.delete("alice"));
        assertNull(service.load("alice"));
        assertNull(service.metadata("alice"));
        assertFalse(Files.exists(dir.resolve("alice.png")));
    }

    /** 构造一个带合法 PNG 魔数的字节数组；服务端只看魔数与长度。 */
    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        for (int i = magic.length; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static final class MemoryAvatars implements AvatarMapper {

        private final Map<String, Avatar> rows = new HashMap<>();

        @Override
        public Avatar select(String username) {
            return rows.get(username);
        }

        @Override
        public int upsert(String username, int version, long updatedAt) {
            Avatar avatar = new Avatar();
            avatar.setUsername(username);
            avatar.setVersion(version);
            avatar.setUpdatedAt(updatedAt);
            rows.put(username, avatar);
            return 1;
        }

        @Override
        public int delete(String username) {
            return rows.remove(username) == null ? 0 : 1;
        }
    }
}
