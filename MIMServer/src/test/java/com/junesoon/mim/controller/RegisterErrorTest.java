package com.junesoon.mim.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.junesoon.mim.exception.GlobalExceptionHandler;
import com.junesoon.mim.exception.UsernameRepeatException;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 用户名重复必须是业务提示，不能变成 HTTP 500 加原始 JSON。
 */
class RegisterErrorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthService authService = new AuthService() {
            @Override
            public String login(JwtAuthenticationRequest authenticationRequest, String deviceId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void register(JwtAuthenticationRequest request) {
                throw new UsernameRepeatException("用户名重复!");
            }

            @Override
            public boolean usernameTaken(String username) {
                return "alice".equals(username);
            }
        };
        RegisterController controller = new RegisterController();
        ReflectionTestUtils.setField(controller, "authService", authService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
    }

    @Test
    @DisplayName("用户名已存在时返回 200，正文只有用户能看懂的那句话")
    void duplicateUsernameIsABusinessMessage() throws Exception {
        mockMvc.perform(post("/register/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"xx\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40002))
            .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @DisplayName("输入完已占用的用户名时，检查接口只回答不可用")
    void takenUsernameIsReportedAsUnavailable() throws Exception {
        mockMvc.perform(get("/register/available").param("username", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(false))
            .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @DisplayName("未被占用的用户名可以继续注册")
    void freeUsernameIsAvailable() throws Exception {
        mockMvc.perform(get("/register/available").param("username", "carol"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }
}
