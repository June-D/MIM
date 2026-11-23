package com.junesoon.mim.requests;

import lombok.Getter;
import lombok.Setter;

/** 上传头像：客户端已把图片重编码成 PNG 再 Base64。 */
@Getter
@Setter
public class AvatarRequest {

    /** PNG 的 Base64；服务端校验魔数与大小，不解析图像内容。 */
    private String image;
}
