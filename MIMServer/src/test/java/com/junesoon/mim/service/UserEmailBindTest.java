package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.impl.UserServiceImpl;
import com.junesoon.mim.utils.EmailCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 找回邮箱的绑定 / 解绑。
 *
 * <p>这是账号恢复通道：只有令牌不够，必须再验证当前密码，
 * 并且解绑要真的把列清空（selective 更新会跳过 null）。
 */
class UserEmailBindTest {

    private static final String PASSWORD = "pass1234";

    private FakeUserMapper users;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        users = new FakeUserMapper();
        users.user.setUsername("alice");
        users.user.setPassword(
            new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT).encode(PASSWORD));
        users.user.setEmailCipher("enc:old@example.com");

        EmailCryptoService crypto = new EmailCryptoService(null) {
            @Override
            public String encrypt(String username, String email) {
                return email == null || email.isEmpty() ? null : "enc:" + email;
            }

            @Override
            public String decrypt(String username, String stored) {
                return stored == null ? "" : stored.replaceFirst("^enc:", "");
            }
        };
        service = new UserServiceImpl(users, null, crypto);
    }

    @Test
    @DisplayName("密码正确时写入新的找回邮箱")
    void bindEmailStoresCipher() {
        service.bindEmail("alice", PASSWORD, " new@example.com ");

        assertEquals("new@example.com", users.lastSaved.getEmailCipher().replaceFirst("^enc:", ""));
        assertTrue(users.lastSaved.getUpdTime() != null);
    }

    @Test
    @DisplayName("密码不正确时拒绝，且不落库")
    void wrongPasswordRejected() {
        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.bindEmail("alice", "wrong-pass", "new@example.com"));
        assertEquals("密码不正确", error.getMessage());
        assertEquals("enc:old@example.com", users.user.getEmailCipher());
    }

    @Test
    @DisplayName("邮箱格式非法时拒绝")
    void invalidEmailRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.bindEmail("alice", PASSWORD, "not-an-email"));
    }

    @Test
    @DisplayName("传空串表示解绑：列被清空而不是保持原值")
    void emptyEmailUnbinds() {
        service.bindEmail("alice", PASSWORD, "");

        assertEquals("", users.lastSaved.getEmailCipher());
    }

    private static final class FakeUserMapper implements UserMapper {

        private final User user = new User();
        private User lastSaved = new User();

        @Override
        public int deleteByPrimaryKey(Integer id) {
            return 0;
        }

        @Override
        public int insert(User record) {
            return 0;
        }

        @Override
        public int insertSelective(User record) {
            return 0;
        }

        @Override
        public User selectByPrimaryKey(Integer id) {
            return user;
        }

        @Override
        public User selectOne(String username) {
            return user.getUsername() != null && user.getUsername().equals(username) ? user : null;
        }

        @Override
        public int updateLastLoginTime(String username, java.util.Date lastLoginTime) {
            return 0;
        }

        @Override
        public int updateByPrimaryKeySelective(User record) {
            lastSaved = record;
            if (record.getEmailCipher() != null) {
                user.setEmailCipher(record.getEmailCipher());
            }
            return 1;
        }

        @Override
        public int updateByPrimaryKey(User record) {
            return 0;
        }
    }
}
