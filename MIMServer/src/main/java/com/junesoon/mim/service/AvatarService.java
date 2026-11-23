package com.junesoon.mim.service;

import com.junesoon.mim.entity.Avatar;

/**
 * 账号头像。
 *
 * <p>头像**不是**端到端加密的：它需要给联系人看，服务端必须能直接返回字节。
 * 客户端在上传前把图片重新编码成 PNG（顺带丢掉 EXIF），所以服务端拿到的
 * 只是一张不含拍摄信息的图片；界面与文档都必须写清这一点。
 */
public interface AvatarService {

    /** 单个头像的上限：客户端已缩到合适尺寸，这里只做兜底。 */
    int MAX_BYTES = 512 * 1024;

    /**
     * 保存头像（必须是 PNG）。返回自增后的版本号。
     *
     * @throws com.junesoon.mim.exception.UserInvalidException 格式或大小不合法
     */
    int save(String username, byte[] png);

    /** 读取头像字节；没有设置过时返回 null。 */
    byte[] load(String username);

    Avatar metadata(String username);

    /** 删除头像；返回是否真的删除了一份。 */
    boolean delete(String username);
}
