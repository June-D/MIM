package com.junesoon.mim.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 致盲签名请求体。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: blinded（Base64URL 大整数）
 * [POS]: TokenFeign.blind 的请求体
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlindSignRequest {

    private String blinded;
}
