package com.junesoon.mim.components;

import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.UserInfo;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 客户端全局状态。
 *
 * <p>注意：这里<b>不再有任何密钥常量</b>。消息加密密钥由
 * {@code E2eeService} 在每次收发时从 ECDH 共享密钥派生，
 * 不做全局静态复用。
 */
public class ApplicatonStore {

    public static final String ICON_FONT_KEY = "icon.svg";
    private static final SimpleStringProperty token = new SimpleStringProperty();
    private static final SimpleObjectProperty<UserInfo> userInfo = new SimpleObjectProperty<>();
    private static final SimpleBooleanProperty online = new SimpleBooleanProperty(false);

    /**
     * 本机设备标识。所有加密相关请求都要带它，因为密钥按设备划分。
     */
    private static volatile String deviceId;
    private static ListProperty<ChatContact> chatContacts;
    public static volatile boolean chatContactsRefresh = false;

    /**
     * 当前登录用户的明文口令，仅在本次会话内驻留内存，
     * 用于在本机解锁/加密身份私钥文件。退出登录时清空。
     */
    private static volatile char[] sessionPassword;

    public static String getToken() {
        return token.get();
    }

    public static void setToken(String value) {
        token.set(value);
    }

    public static UserInfo getUserInfo() {
        return userInfo.get();
    }

    public static void setUserInfo(UserInfo value) {
        userInfo.set(value);
    }

    public static String getUsername() {
        UserInfo info = userInfo.get();
        return info == null ? null : info.getUsername();
    }

    public static String getDeviceId() {
        return deviceId;
    }

    public static void setDeviceId(String value) {
        deviceId = value;
    }

    public static BooleanProperty onlineProperty() {
        return online;
    }

    public static void setOnline(boolean value) {
        online.set(value);
    }

    public static ListProperty<ChatContact> getChatContacts() {
        if (chatContacts == null) {
            ObservableList<ChatContact> innerList = FXCollections.observableArrayList();
            chatContacts = new SimpleListProperty<>(innerList);
        }
        return chatContacts;
    }

    public static void setSessionPassword(char[] password) {
        clearSessionPassword();
        sessionPassword = password;
    }

    public static char[] getSessionPassword() {
        return sessionPassword;
    }

    public static void clearSessionPassword() {
        char[] existing = sessionPassword;
        if (existing != null) {
            java.util.Arrays.fill(existing, '\0');
            sessionPassword = null;
        }
    }

    /**
     * 彻底清理登录态：用于退出登录 / 账号切换。
     *
     * <p>会清空 token 与口令，因此<b>不能</b>在登录流程中间调用。
     * 登录成功后只想重置上一个会话的展示状态时，用 {@link #resetSessionView()}。
     */
    public static void clearUserInfo() {
        setUserInfo(null);
        setToken(null);
        setOnline(false);
        clearSessionPassword();
        getChatContacts().clear();
    }

    /**
     * 只重置会话视图状态（用户信息、联系人列表、在线标记），
     * <b>保留 token 与本机口令</b>。
     *
     * <p>登录流程用这个：token 刚签发、身份密钥刚解锁，
     * 清掉它们会导致后续请求 401「缺少登录凭证」。
     */
    public static void resetSessionView() {
        setUserInfo(null);
        setOnline(false);
        getChatContacts().clear();
    }
}
