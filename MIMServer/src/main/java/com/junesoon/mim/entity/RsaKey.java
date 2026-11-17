package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class RsaKey implements Serializable {
    private String key;

    private String value;

    private static final long serialVersionUID = 1L;

}
