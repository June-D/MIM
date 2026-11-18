package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.RecoveryCode;
import com.junesoon.mim.mapper.RecoveryCodeMapper;
import com.junesoon.mim.service.impl.RecoveryCodeServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecoveryCodeServiceTest {

    private static final class InMemoryMapper implements RecoveryCodeMapper {
        final List<RecoveryCode> rows = new ArrayList<>();
        final AtomicLong sequence = new AtomicLong();

        @Override
        public int deleteAllByUsername(String username) {
            int before = rows.size();
            rows.removeIf(row -> username.equals(row.getUsername()));
            return before - rows.size();
        }

        @Override
        public int insertBatch(List<RecoveryCode> codes) {
            for (RecoveryCode code : codes) {
                code.setId(sequence.incrementAndGet());
                rows.add(code);
            }
            return codes.size();
        }

        @Override
        public List<RecoveryCode> selectUnusedByUsername(String username) {
            List<RecoveryCode> result = new ArrayList<>();
            for (RecoveryCode row : rows) {
                if (username.equals(row.getUsername()) && !Boolean.TRUE.equals(row.getUsed())) {
                    result.add(row);
                }
            }
            return result;
        }

        @Override
        public int markUsed(Long id, long usedAt) {
            for (RecoveryCode row : rows) {
                if (row.getId().equals(id) && !Boolean.TRUE.equals(row.getUsed())) {
                    row.setUsed(true);
                    row.setUsedAt(usedAt);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int countUnused(String username) {
            int count = 0;
            for (RecoveryCode row : rows) {
                if (username.equals(row.getUsername()) && !Boolean.TRUE.equals(row.getUsed())) {
                    count++;
                }
            }
            return count;
        }
    }

    private InMemoryMapper mapper;
    private RecoveryCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = new InMemoryMapper();
        service = new RecoveryCodeServiceImpl(mapper);
    }

    @Test
    @DisplayName("生成 10 个恢复码，只保存哈希，可带/不带连字符消费")
    void generateAndConsume() {
        List<String> codes = service.generate("alice");
        assertEquals(10, codes.size());
        assertEquals(10, service.remaining("alice"));
        assertFalse(mapper.rows.get(0).getCodeHash().contains(codes.get(0)),
            "数据库中不能出现明文恢复码");

        String first = codes.get(0);
        String normalized = first.toLowerCase().replace("-", "");
        assertTrue(service.consume("alice", normalized));
        assertFalse(service.consume("alice", first), "恢复码只能使用一次");
        assertEquals(9, service.remaining("alice"));
        assertFalse(service.consume("alice", "WRONG-CODE-9999"));
    }

    @Test
    @DisplayName("重新生成会清空旧码，deleteAll 清零")
    void regenerateAndDelete() {
        List<String> old = service.generate("alice");
        List<String> fresh = service.generate("alice");
        assertEquals(10, service.remaining("alice"));
        assertFalse(service.consume("alice", old.get(0)), "旧码应已失效");
        assertTrue(service.consume("alice", fresh.get(0)));

        service.deleteAll("alice");
        assertEquals(0, service.remaining("alice"));
        service.deleteAll("alice");
    }
}
