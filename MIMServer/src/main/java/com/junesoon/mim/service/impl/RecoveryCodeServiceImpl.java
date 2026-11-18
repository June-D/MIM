package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.RecoveryCode;
import com.junesoon.mim.mapper.RecoveryCodeMapper;
import com.junesoon.mim.service.RecoveryCodeService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复码使用 32 字符表、12 位（约 60 bit），仅保存 BCrypt 哈希。
 *
 * <p>BCrypt 成本 10：登录时需要逐个比对最多 10 个哈希，成本 12 会让失败登录
 * 明显变慢；恢复码本身熵足够高，10 已足够抵抗离线爆破。
 */
@Service
public class RecoveryCodeServiceImpl implements RecoveryCodeService {

    private static final int CODE_COUNT = 10;
    private static final int GROUPS = 3;
    private static final int GROUP_SIZE = 4;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int BCRYPT_COST = 10;

    private final RecoveryCodeMapper recoveryCodeMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeServiceImpl(RecoveryCodeMapper recoveryCodeMapper) {
        this.recoveryCodeMapper = recoveryCodeMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> generate(String username) {
        recoveryCodeMapper.deleteAllByUsername(username);
        long now = System.currentTimeMillis();
        List<String> display = new ArrayList<>(CODE_COUNT);
        List<RecoveryCode> rows = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = randomCode();
            display.add(code);
            RecoveryCode row = new RecoveryCode();
            row.setUsername(username);
            row.setCodeHash(encoder.encode(normalize(code)));
            row.setUsed(false);
            row.setCreatedAt(now);
            rows.add(row);
        }
        recoveryCodeMapper.insertBatch(rows);
        return display;
    }

    @Override
    public boolean consume(String username, String code) {
        String normalized = normalize(code);
        if (normalized.length() < 8) {
            return false;
        }
        for (RecoveryCode row : recoveryCodeMapper.selectUnusedByUsername(username)) {
            if (encoder.matches(normalized, row.getCodeHash())) {
                return recoveryCodeMapper.markUsed(row.getId(), System.currentTimeMillis()) == 1;
            }
        }
        return false;
    }

    @Override
    public void deleteAll(String username) {
        recoveryCodeMapper.deleteAllByUsername(username);
    }

    @Override
    public int remaining(String username) {
        return recoveryCodeMapper.countUnused(username);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(GROUPS * GROUP_SIZE + GROUPS - 1);
        for (int group = 0; group < GROUPS; group++) {
            if (group > 0) {
                sb.append('-');
            }
            for (int i = 0; i < GROUP_SIZE; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    static String normalize(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
