package com.junesoon.mim.feign.codec;

import com.junesoon.mim.utils.AlertUtil;
import com.junesoon.mim.utils.UserVisibleError;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.datafx.core.concurrent.ProcessChain;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 把 HTTP 非 2xx 响应转成可读异常，并弹出提示。
 */
public class AppRequestErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        String friendly = UserVisibleError.fromTransport(response.status(), readBody(response));
        // 用户名检查是离开输入框时的静默查询，占用与否写在输入框下，不再弹窗。
        boolean quiet = methodKey != null && methodKey.contains("#available");
        if (!quiet && !UserVisibleError.USERNAME_TAKEN.equals(friendly)) {
            ProcessChain.create().addRunnableInPlatformThread(() ->
                AlertUtil.show("提示", friendly)).run();
        }
        return new IllegalStateException(friendly);
    }

    private String readBody(Response response) {
        if (response.body() == null) {
            return response.reason() == null ? "" : response.reason();
        }
        try (InputStream input = response.body().asInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return response.reason() == null ? "" : response.reason();
        }
    }
}
