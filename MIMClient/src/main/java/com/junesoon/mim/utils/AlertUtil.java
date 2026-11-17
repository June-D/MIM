package com.junesoon.mim.utils;

import com.jfoenix.controls.JFXAlert;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.junesoon.mim.components.BaseResponse;
import io.datafx.controller.context.ApplicationContext;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一提示弹窗。
 */
@Slf4j
public class AlertUtil {

    private AlertUtil() {
    }

    public static void show(BaseResponse response) {
        if (response == null) {
            show("提示", "服务端无响应");
            return;
        }
        String message = UserVisibleError.humanize(response.getMessage());
        if (message.isEmpty()) {
            message = "请求没有成功，请稍后再试";
        }
        show("提示", message);
    }

    public static void show(String title, String message) {
        Stage owner = ApplicationContext.getInstance().getRegisteredObject(Stage.class);
        if (owner == null) {
            log.warn("[提示] {}: {}", title, message);
            return;
        }
        JFXAlert alert = new JFXAlert(owner);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setOverlayClose(false);
        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(title));
        layout.setBody(new Label(message == null ? "" : message));
        JFXButton closeButton = new JFXButton("确定");
        closeButton.setOnAction(event -> alert.hideWithAnimation());
        layout.setActions(closeButton);
        alert.setContent(layout);
        alert.show();
    }
}
