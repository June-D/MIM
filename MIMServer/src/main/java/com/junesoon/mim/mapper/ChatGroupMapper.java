package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.ChatGroup;
import java.util.List;

/**
 * @program: MIMServer
 * @description: 联系人Mapper
 * @version: 1.0
 * @create: 2024-03-01 15:25
 */
public interface ChatGroupMapper {

    List<ChatGroup> selectChatGroupListByOwnerUser(String username);
}
