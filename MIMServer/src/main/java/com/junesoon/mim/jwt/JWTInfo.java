package com.junesoon.mim.jwt;

import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JWTInfo implements Serializable {
    private String username;
    private String userId;
    private String nickname;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JWTInfo jwtInfo = (JWTInfo) o;
        if (!Objects.equals(username, jwtInfo.username)) {
            return false;
        }
        return Objects.equals(userId, jwtInfo.userId);
    }

    @Override
    public int hashCode() {
        int result = username != null ? username.hashCode() : 0;
        result = 31 * result + (userId != null ? userId.hashCode() : 0);
        return result;
    }
}
