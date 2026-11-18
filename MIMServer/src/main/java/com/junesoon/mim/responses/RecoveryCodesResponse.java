package com.junesoon.mim.responses;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 重新生成恢复码的结果；明文只返回一次。
 */
@Data
@AllArgsConstructor
public class RecoveryCodesResponse {

    private List<String> recoveryCodes;
}
