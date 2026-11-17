package com.junesoon.mim;

import com.jfoenix.assets.JFoenixResources;
import com.jfoenix.controls.JFXDecorator;
import com.jfoenix.svg.SVGGlyph;
import com.jfoenix.svg.SVGGlyphLoader;
import com.junesoon.mim.gui.LoginController;
import com.junesoon.mim.websocket.WSClient;
import com.netflix.config.ConfigurationManager;
import io.datafx.controller.context.ApplicationContext;
import io.datafx.controller.flow.Flow;
import io.datafx.controller.flow.FlowHandler;
import io.datafx.controller.flow.container.AnimatedFlowContainer;
import io.datafx.controller.flow.container.ContainerAnimations;
import io.datafx.controller.flow.context.FXMLViewFlowContext;
import io.datafx.controller.flow.context.ViewFlowContext;
import java.io.IOException;
import java.util.Objects;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MIMClientStarter extends Application {

    @FXMLViewFlowContext
    private ViewFlowContext flowContext;

    private final ApplicationContext applicationContext = ApplicationContext.getInstance();

    @Override
    public void init() throws Exception {
        ConfigurationManager.loadPropertiesFromResources("feign-client.properties");
    }

    @Override
    public void start(Stage stage) throws Exception {
        new Thread(() -> {
            try {
                SVGGlyphLoader.loadGlyphsFont(
                    MIMClientStarter.class.getResourceAsStream("/fonts/icon_font/iconfont.svg"),
                    "icon.svg");
            } catch (IOException ioExc) {
                log.warn("加载图标字体失败：{}", ioExc.getMessage());
            }
        }, "glyph-loader").start();

        Flow contentFlow = new Flow(LoginController.class);
        AnimatedFlowContainer container = new AnimatedFlowContainer(Duration.millis(320),
            ContainerAnimations.SWIPE_LEFT);
        flowContext = new ViewFlowContext();
        final FlowHandler contentFlowHandler = contentFlow.createHandler(flowContext);
        applicationContext.register(stage, Stage.class);
        applicationContext.register("ContentFlowHandler", contentFlowHandler);
        contentFlowHandler.start(container);

        JFXDecorator decorator = new JFXDecorator(stage, container.getView());
        decorator.setCustomMaximize(true);
        decorator.setGraphic(new SVGGlyph(""));

        Scene scene = new Scene(decorator, 1000, 750);
        stage.setTitle("MIM · 端到端加密即时通讯");
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.show();

        scene.getStylesheets().addAll(JFoenixResources.load("/css/global-fonts.css").toExternalForm(),
            Objects.requireNonNull(MIMClientStarter.class.getResource("/css/login.css")).toExternalForm(),
            Objects.requireNonNull(MIMClientStarter.class.getResource("/css/default-theme.css")).toExternalForm(),
            Objects.requireNonNull(MIMClientStarter.class.getResource("/css/app.css")).toExternalForm());
    }

    @Override
    public void stop() {
        WSClient.discard();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
