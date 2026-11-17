package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.ChatUser;
import com.junesoon.mim.entity.User;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 联系人关系。
 */
public interface ChatUserMapper {

    List<ChatUser> selectChatUserListByOwnerUser(String username);

    /**
     * 模糊搜索可添加的用户：排除自己、排除已是好友的、排除已注销账号。
     *
     * <p>用前缀匹配而非全表 LIKE '%x%'：后者无法走索引，
     * 且好友搜索是高频操作，必须控制住查询成本。
     */
    List<User> searchAddableUsers(@Param("keyword") String keyword,
        @Param("self") String self, @Param("limit") int limit);

    /**
     * 查一条联系人关系（用于判断是否已是好友）。
     */
    ChatUser selectRelation(@Param("ownerUser") String ownerUser,
        @Param("username") String username);

    int insertContact(@Param("ownerUser") String ownerUser, @Param("username") String username,
        @Param("labelName") String labelName);

    int deleteContact(@Param("ownerUser") String ownerUser, @Param("username") String username);

    int updateLabel(@Param("ownerUser") String ownerUser, @Param("username") String username,
        @Param("labelName") String labelName);

    int countContacts(@Param("ownerUser") String ownerUser);
}
