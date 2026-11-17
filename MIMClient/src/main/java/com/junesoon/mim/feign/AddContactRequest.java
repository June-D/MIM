package com.junesoon.mim.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 添加好友请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddContactRequest {

    private String username;

    /**
     * 备注名。为 null 时服务端回退到用户名。
     */
    private String labelName;
}
