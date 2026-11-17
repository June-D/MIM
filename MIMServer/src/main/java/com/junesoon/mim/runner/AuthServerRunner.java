package com.junesoon.mim.runner;

import com.junesoon.mim.config.KeyConfiguration;
import com.junesoon.mim.config.UserAuthConfig;
import com.junesoon.mim.jwt.RsaKeyHelper;
import com.junesoon.mim.service.RsaKeyService;
import java.util.Map;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
public class AuthServerRunner implements CommandLineRunner {

    private static final String USER_PRI_KEY = "MIM:AUTH:JWT:PRI";
    private static final String USER_PUB_KEY = "MIM:AUTH:JWT:PUB";

    @Resource
    private RsaKeyService rsaKeyService;

    @Resource
    private KeyConfiguration keyConfiguration;

    @Resource
    private UserAuthConfig userAuthConfig;

    @Override
    public void run(String... args) throws Exception {
        byte[] pub = null;
        byte[] pri = null;
        if (rsaKeyService.hasKey(USER_PRI_KEY) && rsaKeyService.hasKey(USER_PUB_KEY)) {
            try {
                pub = RsaKeyHelper.decode(rsaKeyService.get(USER_PUB_KEY));
                pri = RsaKeyHelper.decode(rsaKeyService.get(USER_PRI_KEY));
            } catch (Exception e) {
                log.warn("历史 JWT 密钥无法解析，将重新生成", e);
            }
        }
        if (pub == null || pri == null || RsaKeyHelper.keySize(pub) < RsaKeyHelper.KEY_SIZE) {
            log.info("生成新的 RSA {} 位 JWT 密钥对", RsaKeyHelper.KEY_SIZE);
            Map<String, byte[]> keyMap = RsaKeyHelper.generateKey();
            pub = keyMap.get("pub");
            pri = keyMap.get("pri");
            rsaKeyService.set(USER_PRI_KEY, RsaKeyHelper.encode(pri));
            rsaKeyService.set(USER_PUB_KEY, RsaKeyHelper.encode(pub));
        }
        keyConfiguration.setUserPubKey(pub);
        keyConfiguration.setUserPriKey(pri);
        refreshUserPubKey();
    }

    /**
     * 兜底刷新，保证多实例/热更新场景下公钥可用。
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void refreshUserPubKey() {
        this.userAuthConfig.setPubKeyByte(keyConfiguration.getUserPubKey());
    }
}
