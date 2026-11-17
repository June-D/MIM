package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.FriendRequest;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 好友申请。同意之前不写联系人表。
 */
public interface FriendRequestMapper {

    int insert(FriendRequest request);

    FriendRequest selectById(@Param("id") long id);

    FriendRequest selectByPair(@Param("fromUser") String fromUser, @Param("toUser") String toUser);

    /**
     * 把已拒绝或已同意的旧申请重新打开为待处理。
     *
     * @return 影响行数。0 表示状态已被别人改掉
     */
    int reopen(@Param("id") long id, @Param("updatedAt") long updatedAt);

    /**
     * 仅当当前仍是待处理时，改成同意或拒绝。
     *
     * @return 影响行数。0 表示申请已被处理
     */
    int resolve(@Param("id") long id, @Param("status") int status, @Param("updatedAt") long updatedAt);

    /**
     * 与我有关、且仍待处理的申请（我发出的和发给我的）。
     */
    List<FriendRequest> selectPending(@Param("username") String username);
}
