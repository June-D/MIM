package com.junesoon.mim.model;

import java.util.List;
import lombok.Data;

/**
 * 重新生成恢复码的结果。
 */
@Data
public class RecoveryCodesResponse {

    private List<String> recoveryCodes;
}
