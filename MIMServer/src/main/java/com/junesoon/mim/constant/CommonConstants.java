package com.junesoon.mim.constant;

public class CommonConstants {

    private CommonConstants() {
    }

    public static final int PW_ENCORDER_SALT = 12;

    // 用户token异常
    public static final Integer EX_USER_INVALID_CODE = 40101;
    public static final Integer EX_USER_PASS_INVALID_CODE = 40001;
    /** 需要二次验证码：客户端据此弹出验证码输入框并重试登录。 */
    public static final Integer EX_2FA_REQUIRED_CODE = 40102;
    // 用户名重复
    public static final Integer EX_USERNAME_REPEAT_INVALID_CODE = 40002;
    // 客户端token异常
    public static final Integer EX_CLIENT_INVALID_CODE = 40301;
    public static final Integer EX_CLIENT_FORBIDDEN_CODE = 40331;
    public static final Integer EX_OTHER_CODE = 500;
    // 请求过于频繁
    public static final Integer EX_RATE_LIMIT_CODE = 42901;

    public static final String CONTEXT_KEY_USER_ID = "currentUserId";
    public static final String CONTEXT_KEY_USERNAME = "currentUserName";
    public static final String CONTEXT_KEY_USER_NAME = "currentUser";
    public static final String CONTEXT_KEY_USER_TOKEN = "currentUserToken";

    public static final String JWT_KEY_USER_ID = "userId";
    public static final String JWT_KEY_NICKNAME = "nickname";
    /** 令牌绑定的设备标识。缺省为空表示旧版未绑定设备。 */
    public static final String JWT_KEY_DEVICE_ID = "deviceId";
    /** 设备的令牌版本。登出/注销时自增，旧令牌立即失效。 */
    public static final String JWT_KEY_TOKEN_VERSION = "tv";

    public static final String USER_STATUS_NORMAL = "1";
    public static final String USER_STATUS_LOGOFF = "2";

    /**
     * 当前用户协议版本。客户端注册时必须勾选同意并携带同一版本。
     */
    public static final String TERMS_VERSION = "2026-12";

    public static final String WEB_SOCKET_USER_LIST = "web_socket_user_list";
    public static final String WEB_SOCKET_USER_ID = "web_socket_user_id";
    /** 握手时写入 session 属性的设备标识，用于按设备投递 */
    public static final String WEB_SOCKET_DEVICE_ID = "web_socket_device_id";
    /** 握手时写入的 JWT 过期时间，用于定时关闭已过期的长连接 */
    public static final String WEB_SOCKET_TOKEN_EXPIRES_AT = "web_socket_token_expires_at";
    public static final String WEB_SOCKET_TOKEN = "WEB_SOCKET_TOKEN";

    // ---------------- WebSocket 通知类型 ----------------

    /** 连接成功 */
    public static final Integer NOTIFY_TYPE_CONNECT_SUCCESS = 100000;
    /** 联系人上线 */
    public static final Integer NOTIFY_TYPE_USER_ONLINE = 100001;
    /** 联系人离线 */
    public static final Integer NOTIFY_TYPE_USER_OFFLINE = 100002;
    /** 收到新消息（载荷为密文消息） */
    public static final Integer NOTIFY_TYPE_NEW_MESSAGE = 100010;
    /** 对端已读回执 */
    public static final Integer NOTIFY_TYPE_READ_RECEIPT = 100012;
    /** 消息被撤回 */
    public static final Integer NOTIFY_TYPE_MESSAGE_RECALLED = 100014;
    /** 有人向我申请好友 */
    public static final Integer NOTIFY_TYPE_FRIEND_REQUEST = 100016;
    /** 对方同意了我的好友申请 */
    public static final Integer NOTIFY_TYPE_FRIEND_ACCEPTED = 100017;
    /** 对方拒绝了我的好友申请 */
    public static final Integer NOTIFY_TYPE_FRIEND_REJECTED = 100018;
    /** 会话消息被清除。载荷里的 peer 是接收方要清空的那个对话对象 */
    public static final Integer NOTIFY_TYPE_CONVERSATION_CLEARED = 100019;
    /** 心跳 */
    public static final Integer NOTIFY_TYPE_PING = 100020;
    /** 心跳响应 */
    public static final Integer NOTIFY_TYPE_PONG = 100021;
    /**
     * 请其他设备把本机还没有的历史，用设备间会话重新加密后交过来。
     * 载荷 {@code {deviceId}} 是需要历史的那台设备。
     */
    public static final Integer NOTIFY_TYPE_HISTORY_SYNC = 100022;
    /** 新登录/新设备安全提醒，只发给该账号的其他在线设备 */
    public static final Integer NOTIFY_TYPE_SECURITY_ALERT = 100023;

    /**
     * 已读状态在本人设备间的同步（100024）。
     *
     * <p>只发给「同一账号的其它设备」，payload 为 {@code {peer}}，不含任何内容：
     * 收到后重新拉一次未读数即可。
     */
    public static final Integer NOTIFY_TYPE_READ_SYNC = 100024;

    /**
     * 跨实例消息路由的 Redis 频道。所有实例都订阅，收到后只投递给本机在线会话。
     */
    public static final String REDIS_PUSH_CHANNEL = "mim:ws:push";
}
