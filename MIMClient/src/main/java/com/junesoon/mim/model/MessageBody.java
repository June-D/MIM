package com.junesoon.mim.model;

import lombok.Data;

@Data
public class MessageBody {

    private String fromUsername;

    private String toUsername;

    private String message;

    private Long timestamp;
}
