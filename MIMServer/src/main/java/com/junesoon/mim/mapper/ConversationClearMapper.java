package com.junesoon.mim.mapper;

import com.junesoon.mim.responses.ClearPoint;
import org.apache.ibatis.annotations.Param;

/**
 * 会话清除点。每个用户对自己和某个对端各有一条，多实例下以数据库为准。
 */
public interface ConversationClearMapper {

    Long selectCursor(@Param("username") String username, @Param("peer") String peer);

    /**
     * 清除点与清除时间。没有清除记录时返回 null。
     *
     * <p>客户端用它（而不是只拿 id）判断本机缓存里哪条消息属于被清掉的范围。
     */
    ClearPoint selectPoint(@Param("username") String username, @Param("peer") String peer);

    int insert(@Param("username") String username, @Param("peer") String peer,
        @Param("clearedBeforeId") long clearedBeforeId, @Param("updatedAt") long updatedAt);

    /**
     * 只允许把清除点往前推，不能把已经清掉的消息重新露出来。
     *
     * <p>这些方法必须是抽象的。写成 default 后 MyBatis 不会执行 XML，清除点永远写不进数据库。
     */
    int advance(@Param("username") String username, @Param("peer") String peer,
        @Param("clearedBeforeId") long clearedBeforeId, @Param("updatedAt") long updatedAt);
}
