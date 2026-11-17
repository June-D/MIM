package com.junesoon.mim.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @program: MIMServer
 * @description: 消息体
 * @version: 1.0
 * @create: 2024-03-07 15:16
 */
@Data
public class MessageBody {
    private String fromUsername;

    private String toUsername;

    private String message;

    private Long timestamp;
}
