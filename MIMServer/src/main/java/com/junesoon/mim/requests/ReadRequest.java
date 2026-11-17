package com.junesoon.mim.requests;

import lombok.Data;

@Data
public class ReadRequest {

    private String peer;

    private Long beforeId;
}
