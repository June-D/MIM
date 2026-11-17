package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.OneTimePreKeyUpload;
import com.junesoon.mim.model.PreKeyBundle;
import com.junesoon.mim.model.SignedPreKeyUpload;
import feign.Param;
import feign.RequestLine;
import java.util.List;
import java.util.Map;

/**
 * 预密钥包接口。所有操作按设备维度。
 */
public interface PreKeyFeign extends FeignAPI {

    @RequestLine("POST /prekey/signed")
    ObjectRestResponse<Map<String, Object>> uploadSigned(SignedPreKeyUpload request);

    @RequestLine("POST /prekey/one-time")
    ObjectRestResponse<Integer> uploadOneTime(List<OneTimePreKeyUpload> keys);

    @RequestLine("GET /prekey/status")
    ObjectRestResponse<Map<String, Object>> status();

    /**
     * 拉取对方所有有效设备的预密钥包。
     */
    @RequestLine("GET /prekey/{username}")
    ObjectRestResponse<List<PreKeyBundle>> fetch(@Param("username") String username);
}
