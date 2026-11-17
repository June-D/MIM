package com.junesoon.mim.gui;

import com.jfoenix.validation.base.ValidatorBase;
import com.junesoon.mim.utils.UserVisibleError;

/**
 * 用户名已被占用。是否占用由离开输入框时的查询结果决定，不在这里发请求。
 */
public class UsernameTakenValidator extends ValidatorBase {

    private boolean taken;

    public UsernameTakenValidator() {
        super(UserVisibleError.USERNAME_TAKEN);
    }

    public void setTaken(boolean taken) {
        this.taken = taken;
    }

    @Override
    protected void eval() {
        hasErrors.set(taken);
    }
}
