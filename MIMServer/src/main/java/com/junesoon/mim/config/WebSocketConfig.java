package com.junesoon.mim.config;

import com.junesoon.mim.interceptor.WebcocketInterceptor;
import com.junesoon.mim.websocket.WebSocketServer;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private WebSocketServer webSocketServer;
    @Resource
    private WebcocketInterceptor myInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketServer, "/websocket/*")
            .addInterceptors(myInterceptor)
            .setAllowedOrigins("*");
    }
}
