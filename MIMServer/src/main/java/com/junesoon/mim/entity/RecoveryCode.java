package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 一次性恢复码，仅保存 BCrypt 哈希。
 */
@Data
public class RecoveryCode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String codeHash;

    private Boolean used;

    private Long createdAt;

    private Long usedAt;
}
