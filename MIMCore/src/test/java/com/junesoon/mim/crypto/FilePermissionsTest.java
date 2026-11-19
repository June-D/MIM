package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilePermissionsTest {

    @Test
    @DisplayName("敏感文件只保留属主读写权限（POSIX）")
    void ownerOnly() throws Exception {
        Path file = Files.createTempFile("mim-perm-", ".tmp");
        try {
            FilePermissions.ownerOnly(file);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
                assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
                assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
                assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
                assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
                assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
