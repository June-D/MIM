package com.junesoon.mim.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改备注名请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RenameRequest {

    private String labelName;
}
