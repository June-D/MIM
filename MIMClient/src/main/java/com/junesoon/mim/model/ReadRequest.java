package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已读回执请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadRequest {

    private String peer;

    private Long beforeId;
}
