package com.junesoon.mim.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地敏感文件权限：POSIX 上设 0600，Windows 上设置只允许当前用户访问的 ACL。
 *
 * <p>Windows 会忽略 POSIX 权限，只调 {@code setPosixFilePermissions} 等于没有保护。
 */
@Slf4j
public final class FilePermissions {

    private FilePermissions() {
    }

    public static void ownerOnly(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(file, perms);
            return;
        } catch (UnsupportedOperationException | IOException e) {
            // Windows / 非 POSIX 文件系统，继续尝试 ACL
        }
        applyOwnerOnlyAcl(file);
    }

    private static void applyOwnerOnlyAcl(Path file) {
        try {
            AclFileAttributeView view =
                Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                log.debug("文件系统不支持 ACL，无法限制文件权限：{}", file);
                return;
            }
            UserPrincipal owner = view.getOwner();
            if (owner == null) {
                String name = System.getProperty("user.name");
                owner = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(name);
                view.setOwner(owner);
            }
            AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.of(
                    AclEntryPermission.READ_DATA,
                    AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.WRITE_ATTRIBUTES,
                    AclEntryPermission.READ_ACL,
                    AclEntryPermission.WRITE_ACL,
                    AclEntryPermission.DELETE,
                    AclEntryPermission.SYNCHRONIZE))
                .build();
            view.setAcl(java.util.Collections.singletonList(entry));
        } catch (Exception e) {
            log.debug("设置文件 ACL 失败：{}", e.getMessage());
        }
    }
}
