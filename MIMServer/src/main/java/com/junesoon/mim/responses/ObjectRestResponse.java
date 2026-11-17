package com.junesoon.mim.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectRestResponse<T> extends BaseResponse {

    private T data;
    private boolean rel;

    public ObjectRestResponse rel(boolean rel) {
        this.setRel(rel);
        return this;
    }

    public ObjectRestResponse data(T data) {
        this.setData(data);
        return this;
    }
}
