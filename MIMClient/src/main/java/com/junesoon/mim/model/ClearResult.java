package com.junesoon.mim.model;

import lombok.Data;

@Data
public class ClearResult {

    private String peer;

    private long clearedBeforeId;

    private boolean forBoth;
}
