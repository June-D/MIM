package com.junesoon.mim.gui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXProgressBar;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.svg.SVGGlyph;
import com.jfoenix.svg.SVGGlyphLoader;
import com.jfoenix.validation.RegexValidator;
import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.feign.LoginFeign;
import com.junesoon.mim.feign.RegisterFeign;
import com.junesoon.mim.model.JwtAuthenticationRequest;
import com.junesoon.mim.model.UserInfo;
import com.junesoon.mim.service.HistorySync;
import com.junesoon.mim.service.KeyManager;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.service.PreKeyManager;
import com.junesoon.mim.utils.AlertUtil;
import com.junesoon.mim.utils.CommonUtil;
import com.junesoon.mim.utils.EncryptUtil;
import com.junesoon.mim.utils.PathUtils;
import com.junesoon.mim.utils.UserVisibleError;
import com.junesoon.mim.websocket.WSClient;
import io.datafx.controller.ViewController;
import io.datafx.controller.flow.action.ActionMethod;
import io.datafx.controller.flow.action.ActionTrigger;
import io.datafx.controller.flow.context.ActionHandler;
import io.datafx.controller.flow.context.FlowActionHandler;
import io.datafx.core.concurrent.ProcessChain;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.effect.PerspectiveTransform;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ViewController("/fxml/login.fxml")
public class LoginController {

    /**
     * 服务端返回该状态码表示密码已通过，需要补充 TOTP 验证码。
     */
    private static final int STATUS_2FA_REQUIRED = 40102;

    @ActionHandler
    private FlowActionHandler actionHandler;
    private final UsernameTakenValidator usernameTakenValidator = new UsernameTakenValidator();
    private final AtomicInteger usernameCheckGeneration = new AtomicInteger();
    @FXML
    private StackPane rootPane;
    @FXML
    private Pane imagePane;
    @FXML
    private StackPane centerPane;
    /*正面视图*/
    @FXML
    public HBox loginPane;
    /*反面视图*/
    @FXML
    public HBox registeredPane;
    @FXML
    public Hyperlink registeredLink;
    @FXML
    public Hyperlink loginLink;

    @FXML
    public Hyperlink recoverLink;
    @FXML
    private Label errorLabel;
    @FXML
    private Label errorLabel1;
    @FXML
    private Label userIcon;
    @FXML
    private Label pwdIcon;
    @FXML
    private Label reuserIcon;
    @FXML
    private Label repwdIcon;
    @FXML
    private Label repwd2Icon;
    @FXML
    private JFXProgressBar lodingBar;
    @FXML
    private JFXTextField userNameTextField;
    @FXML
    private JFXPasswordField passWordTextField;
    @FXML
    private JFXTextField reUserNameTextField;
    @FXML
    private JFXPasswordField rePwdTextField;
    @FXML
    private JFXPasswordField rePwd2TextField;
    @FXML
    private RegexValidator regexValidatorPwd2;
    /*翻转角度*/
    private DoubleProperty angleProperty = new SimpleDoubleProperty(Math.PI / 2);
    /*正面翻转特效*/
    private PerspectiveTransform frontEffect = new PerspectiveTransform();
    /*反面翻转特效*/
    private PerspectiveTransform backEffect = new PerspectiveTransform();
    private Timeline frontTimeLine = new Timeline();
    private Timeline backTimeLine = new Timeline();

    private SequentialTransition sequentialTransition = new SequentialTransition();

    private DoubleProperty imageWidth = new SimpleDoubleProperty();
    private DoubleProperty imageHeiht = new SimpleDoubleProperty();

    @FXML
    @ActionTrigger("login")
    private JFXButton loginBut;

    @FXML
    @ActionTrigger("register")
    private JFXButton registeredBut;

    @PostConstruct
    public void init() {
        try {
            SVGGlyph userSvg = SVGGlyphLoader.getIcoMoonGlyph(ApplicatonStore.ICON_FONT_KEY + ".user-name");
            SVGGlyph pwdSvg = SVGGlyphLoader.getIcoMoonGlyph(ApplicatonStore.ICON_FONT_KEY + ".password");
            SVGGlyph reuserSvg = SVGGlyphLoader.getIcoMoonGlyph(ApplicatonStore.ICON_FONT_KEY + ".user-name");
            SVGGlyph repwdSvg = SVGGlyphLoader.getIcoMoonGlyph(ApplicatonStore.ICON_FONT_KEY + ".password");
            SVGGlyph repwd2Svg = SVGGlyphLoader.getIcoMoonGlyph(ApplicatonStore.ICON_FONT_KEY + ".querenmima");
            userSvg.setId("login-svg-glyph");
            pwdSvg.setId("login-svg-glyph");
            reuserSvg.setId("login-svg-glyph");
            repwdSvg.setId("login-svg-glyph");
            repwd2Svg.setId("login-svg-glyph");

            userIcon.setGraphic(userSvg);
            pwdIcon.setGraphic(pwdSvg);
            reuserIcon.setGraphic(reuserSvg);
            repwdIcon.setGraphic(repwdSvg);
            repwd2Icon.setGraphic(repwd2Svg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        imagePane.widthProperty().addListener((observable, oldValue, newValue) -> imageWidth.setValue(newValue));
        imagePane.heightProperty().addListener((observable, oldValue, newValue) -> imageHeiht.setValue(newValue));

        registeredPane.visibleProperty().bind(
            Bindings.when(angleProperty.lessThan(0)).then(true).otherwise(false));
        loginPane.visibleProperty().bind(registeredPane.visibleProperty().not());
        loginPane.managedProperty().bind(loginPane.visibleProperty());
        registeredPane.managedProperty().bind(registeredPane.visibleProperty());

        initAnimation();
        loadingImage();
        initAction();
    }

    private void initAnimation() {
        FadeTransition fadeTransition = new FadeTransition(Duration.millis(500), centerPane);
        fadeTransition.setFromValue(0f);
        fadeTransition.setToValue(1f);

        TranslateTransition translateTransition = new TranslateTransition(Duration.millis(500), centerPane);
        translateTransition.setInterpolator(Interpolator.EASE_BOTH);
        translateTransition.setFromY(400);
        translateTransition.setToY(centerPane.getLayoutY());

        ParallelTransition parallelTransition = new ParallelTransition();
        parallelTransition.setDelay(Duration.millis(1000));
        parallelTransition.getChildren().addAll(
            fadeTransition,
            translateTransition
        );
        parallelTransition.setCycleCount(1);
        parallelTransition.setOnFinished(event -> userNameTextField.requestFocus());
        parallelTransition.play();

        KeyFrame frame1 = new KeyFrame(Duration.ZERO, new KeyValue(angleProperty,
            Math.PI / 2, Interpolator.LINEAR));
        KeyFrame frame2 = new KeyFrame(Duration.seconds(0.7),
            new EventHandler() {
                @Override
                public void handle(Event event) {
                    loginPane.setEffect(null);
                    registeredPane.setEffect(null);
                }

            }, new KeyValue(angleProperty, -Math.PI / 2, Interpolator.LINEAR));
        KeyFrame frame3 = new KeyFrame(Duration.seconds(.7), new EventHandler() {
            @Override
            public void handle(Event event) {
                loginPane.setEffect(null);
                registeredPane.setEffect(null);
            }
        }, new KeyValue(angleProperty,
            Math.PI / 2, Interpolator.LINEAR));
        KeyFrame frame4 = new KeyFrame(Duration.ZERO, new KeyValue(angleProperty, -Math.PI / 2, Interpolator.LINEAR));

        frontTimeLine.getKeyFrames().addAll(frame1, frame2);
        backTimeLine.getKeyFrames().addAll(frame4, frame3);
    }

    @SneakyThrows
    private void loadingImage() {
        // 轮播图只加载实际存在的资源：原代码硬编码了 img_4.png 与 images/login.jpg，
        // 两者在生产资源里都不存在，会直接抛 Invalid URL 让整个界面加载失败。
        String[] children = {"img.png", "img_1.jpg", "img_2.png", "img_3.png"};
        if (imagePane.getChildren().isEmpty()) {
            // 首帧用第一张图占位，避免轮播开始前出现空白
            ImageView placeholder = new ImageView("/images/login/img.png");
            placeholder.fitHeightProperty().bind(imageHeiht);
            placeholder.fitWidthProperty().bind(imageWidth);
            imagePane.getChildren().add(new Label("", placeholder));
        }
        sequentialTransition.setAutoReverse(true);
        sequentialTransition.setCycleCount(Timeline.INDEFINITE);
        ProcessChain.create().addPublishingTask(() -> imagePane.getChildren(), p -> {
            for (String fileName : children) {
                String url = "/images/login/" + fileName;
                if (getClass().getResource(url) == null) {
                    // 资源缺失时跳过，不让单张图缺失拖垮整个界面
                    log.warn("登录页素材缺失，已跳过：{}", url);
                    continue;
                }
                if (PathUtils.isJar()) {
                    url = String.format("jar:file:%s!%s", PathUtils.getJarPath(), url);
                }
                ImageView imageView = new ImageView(url);
                imageView.fitHeightProperty().bind(imageHeiht);
                imageView.fitWidthProperty().bind(imageWidth);
                Label label = new Label("", imageView);
                label.setOpacity(0d);
                FadeTransition fadeT = new FadeTransition(Duration.millis(500), label);
                fadeT.setDelay(Duration.millis(1500));
                fadeT.setFromValue(0f);
                fadeT.setToValue(1f);
                fadeT.setCycleCount(1);
                sequentialTransition.getChildren().add(fadeT);
                p.publish(label);
            }
        }).withFinal(() -> sequentialTransition.play()).run();
    }

    private void initAction() {
        reUserNameTextField.getValidators().add(usernameTakenValidator);

        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        errorLabel1.visibleProperty().bind(errorLabel1.textProperty().isNotEmpty());
        errorLabel1.managedProperty().bind(errorLabel1.visibleProperty());

        lodingBar.visibleProperty().bind(centerPane.disableProperty());
        lodingBar.managedProperty().bind(lodingBar.visibleProperty());

        userNameTextField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                userNameTextField.validate();
            }
        });
        passWordTextField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                passWordTextField.validate();
            }
        });

        reUserNameTextField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                reUserNameTextField.validate();
                checkUsernameAvailable();
            }
        });
        rePwdTextField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                rePwdTextField.validate();
            }
        });
        rePwd2TextField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                regexValidatorPwd2.setRegexPattern("^" + rePwdTextField.getText() + "$");
                rePwd2TextField.validate();
            }
        });

        reUserNameTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            usernameCheckGeneration.incrementAndGet();
            usernameTakenValidator.setTaken(false);
            errorLabel1.setText("");
            reUserNameTextField.validate();
        });
        rePwdTextField.textProperty().addListener((o, oldVal, newVal) -> {
            rePwdTextField.validate();

        });
        rePwd2TextField.textProperty().addListener((o, oldVal, newVal) -> {
            regexValidatorPwd2.setRegexPattern("^" + rePwdTextField.getText() + "$");
            rePwd2TextField.validate();
        });

        loginBut.disableProperty().bind(Bindings.or(
            userNameTextField.textProperty().isEqualTo(""),
            passWordTextField.textProperty().isEqualTo("")));

        BooleanBinding b1 = Bindings.or(
            reUserNameTextField.textProperty().isEqualTo(""),
            rePwdTextField.textProperty().isEqualTo(""));

        BooleanBinding b2 = Bindings.or(b1,
            rePwd2TextField.textProperty().isEqualTo(""));

        BooleanBinding b3 = Bindings.or(reUserNameTextField.activeValidatorProperty().isNotNull(), rePwdTextField.activeValidatorProperty().isNotNull());
        BooleanBinding b4 = Bindings.or(b3, rePwd2TextField.activeValidatorProperty().isNotNull());

        registeredBut.disableProperty().bind(Bindings.or(b2, b4));

        rootPane.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (!loginBut.isDisable()) {
                    login();
                } else if (!registeredBut.isDisable()) {
                    register();
                }
            }
        });
        angleProperty.addListener((observable, oldValue, newValue) -> {
            CommonUtil.setPT(frontEffect, angleProperty.get());
            CommonUtil.setPT(backEffect, angleProperty.get() - Math.PI);
        });
        registeredLink.setOnAction(event -> {
            loginPane.setEffect(frontEffect);
            registeredPane.setEffect(backEffect);
            frontTimeLine.play();
        });
        loginLink.setOnAction(event -> {
            loginPane.setEffect(frontEffect);
            registeredPane.setEffect(backEffect);
            backTimeLine.play();
        });
        recoverLink.setOnAction(event -> showRecoveryDialog());
    }

    /**
     * 注册前收集可选邮箱与用户协议同意状态。
     */
    private static final class RegistrationInfo {
        private final String email;
        private final String termsVersion;

        RegistrationInfo(String email, String termsVersion) {
            this.email = email;
            this.termsVersion = termsVersion;
        }
    }

    /**
     * 注册信息对话框：邮箱可选（仅本人可见，用于找回密码），必须勾选同意用户协议。
     */
    private RegistrationInfo askRegistrationInfo() {
        String retainedEmail = "";
        while (true) {
            javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
                new javafx.scene.control.Dialog<>();
            dialog.setTitle("注册信息");
            dialog.setHeaderText("邮箱可选，仅本人可见，用于找回密码");
            javafx.scene.control.TextField emailField =
                new javafx.scene.control.TextField(retainedEmail);
            emailField.setPromptText("邮箱（可选，请填写真实有效地址）");
            javafx.scene.control.Label hint = new javafx.scene.control.Label(
                "邮箱不做真实性验证；请务必填写可用邮箱，否则将无法找回密码。");
            hint.setWrapText(true);
            javafx.scene.control.CheckBox agree = new javafx.scene.control.CheckBox(
                "我已阅读并同意《用户协议》");
            javafx.scene.control.Hyperlink terms =
                new javafx.scene.control.Hyperlink("查看用户协议");
            terms.setOnAction(e -> showTermsDialog());
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10,
                new javafx.scene.control.Label("邮箱（可选）"), emailField, hint, agree, terms);
            box.setPrefWidth(480);
            dialog.getDialogPane().setContent(box);
            dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            java.util.Optional<javafx.scene.control.ButtonType> result = dialog.showAndWait();
            if (!result.isPresent() || result.get() != javafx.scene.control.ButtonType.OK) {
                return null;
            }
            retainedEmail = emailField.getText() == null ? "" : emailField.getText().trim();
            if (!agree.isSelected()) {
                AlertUtil.show("请先同意用户协议", "必须勾选同意《用户协议》才能注册。");
                continue;
            }
            return new RegistrationInfo(retainedEmail,
                com.junesoon.mim.utils.UserAgreement.VERSION);
        }
    }

    private void showTermsDialog() {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
            new javafx.scene.control.Dialog<>();
        dialog.setTitle("用户协议");
        dialog.setHeaderText("请阅读以下条款与风险提示");
        javafx.scene.control.TextArea text = new javafx.scene.control.TextArea(
            com.junesoon.mim.utils.UserAgreement.TEXT);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(18);
        text.setPrefColumnCount(52);
        dialog.getDialogPane().setContent(text);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /**
     * 邮箱验证码找回密码。
     */
    private void showRecoveryDialog() {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
            new javafx.scene.control.Dialog<>();
        dialog.setTitle("找回密码");
        dialog.setHeaderText("通过注册邮箱验证码重置密码；重置会同时关闭二次验证");

        javafx.scene.control.TextField username = new javafx.scene.control.TextField();
        username.setPromptText("用户名");
        javafx.scene.control.TextField code = new javafx.scene.control.TextField();
        code.setPromptText("邮箱验证码");
        javafx.scene.control.PasswordField password =
            new javafx.scene.control.PasswordField();
        password.setPromptText("新密码（至少 6 位）");
        javafx.scene.control.PasswordField confirm =
            new javafx.scene.control.PasswordField();
        confirm.setPromptText("确认新密码");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new javafx.scene.control.Label("用户名"), 0, 0);
        grid.add(username, 1, 0);
        grid.add(new javafx.scene.control.Label("邮箱验证码"), 0, 1);
        grid.add(code, 1, 1);
        grid.add(new javafx.scene.control.Label("新密码"), 0, 2);
        grid.add(password, 1, 2);
        grid.add(new javafx.scene.control.Label("确认新密码"), 0, 3);
        grid.add(confirm, 1, 3);
        dialog.getDialogPane().setContent(grid);

        javafx.scene.control.ButtonType send = new javafx.scene.control.ButtonType(
            "发送验证码", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType reset = new javafx.scene.control.ButtonType(
            "重置密码", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
            "取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(send, reset, cancel);
        dialog.getDialogPane().lookupButton(send).addEventFilter(
            javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                sendRecoveryCode(username.getText());
            });
        dialog.getDialogPane().lookupButton(reset).addEventFilter(
            javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                String user = username.getText() == null ? "" : username.getText().trim();
                if (user.isEmpty()) {
                    AlertUtil.show("无法重置", "请输入用户名。");
                    return;
                }
                String value = password.getText() == null ? "" : password.getText();
                if (value.length() < 6) {
                    AlertUtil.show("无法重置", "新密码至少 6 位。");
                    return;
                }
                if (!value.equals(confirm.getText())) {
                    AlertUtil.show("无法重置", "两次输入的新密码不一致。");
                    return;
                }
                resetRecoveredPassword(dialog, reset, user, code.getText(), value);
            });
        dialog.showAndWait();
    }

    private void sendRecoveryCode(String username) {
        if (username == null || username.trim().isEmpty()) {
            AlertUtil.show("提示", "请输入用户名。");
            return;
        }
        final String user = username.trim();
        new Thread(() -> {
            try {
                ObjectRestResponse<Boolean> resp = Request.connector(LoginFeign.class)
                    .recoverEmail(new com.junesoon.mim.model.RecoverEmailRequest(user));
                String message = resp != null && resp.getStatus() == 200
                    ? "如果该账号填写了邮箱，验证码已发送，请查收（包括垃圾邮件箱）。"
                    : (resp == null ? "服务端无响应" : resp.getMessage());
                javafx.application.Platform.runLater(() -> AlertUtil.show("提示", message));
            } catch (Exception e) {
                javafx.application.Platform.runLater(
                    () -> AlertUtil.show("发送失败", String.valueOf(e.getMessage())));
            }
        }, "recover-send").start();
    }

    private void resetRecoveredPassword(
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog,
        javafx.scene.control.ButtonType resetType, String username, String code,
        String newPassword) {
        final String recoveryCode = code == null ? "" : code.trim();
        new Thread(() -> {
            try {
                ObjectRestResponse<Boolean> resp = Request.connector(LoginFeign.class)
                    .recoverReset(new com.junesoon.mim.model.RecoverResetRequest(username,
                        recoveryCode, EncryptUtil.base64Encode(newPassword)));
                if (resp != null && resp.getStatus() == 200) {
                    javafx.application.Platform.runLater(() -> {
                        dialog.setResult(resetType);
                        dialog.close();
                        AlertUtil.show("密码已重置",
                            "请使用新密码登录；该账号的二次验证已关闭。");
                    });
                } else {
                    String message = resp == null ? "服务端无响应" : resp.getMessage();
                    javafx.application.Platform.runLater(
                        () -> AlertUtil.show("重置失败", message));
                }
            } catch (Exception e) {
                javafx.application.Platform.runLater(
                    () -> AlertUtil.show("重置失败", String.valueOf(e.getMessage())));
            }
        }, "recover-reset").start();
    }

    @ActionMethod("register")
    private void register() {
        RegistrationInfo info = askRegistrationInfo();
        if (info == null) {
            return;
        }
        JwtAuthenticationRequest jwtAuthenticationRequest = new JwtAuthenticationRequest(
            reUserNameTextField.getText(), EncryptUtil.base64Encode(rePwdTextField.getText()));
        jwtAuthenticationRequest.setEmail(info.email);
        jwtAuthenticationRequest.setAgreedTerms(Boolean.TRUE);
        jwtAuthenticationRequest.setTermsVersion(info.termsVersion);
        final AtomicBoolean succeeded = new AtomicBoolean(false);
        ProcessChain.create().addRunnableInPlatformThread(() -> {
            centerPane.setDisable(true);
            registeredBut.setText("正在注册...");
        }).addSupplierInExecutor(() -> {
            return Request.connector(RegisterFeign.class).register(jwtAuthenticationRequest);
        }).addConsumerInPlatformThread(rel -> {
            if (rel != null && rel.getStatus() == 200) {
                succeeded.set(true);
                errorLabel1.setText("");
                usernameTakenValidator.setTaken(false);
            } else {
                showRegisterRejection(rel == null ? null : rel.getMessage());
            }
        }).addRunnableInPlatformThread(() -> {
            if (!succeeded.get()) {
                return;
            }
            try {
                actionHandler.navigate(ToLoginController.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).addSupplierInExecutor(() -> {
            if (!succeeded.get()) {
                return null;
            }
            try {
                Thread.sleep(2000);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }).addConsumerInPlatformThread(rel -> {
            if (!succeeded.get()) {
                return;
            }
            try {
                actionHandler.navigate(LoginController.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).onException(e -> {
            succeeded.set(false);
            if (isConnectionFailure(e)) {
                errorLabel1.setText("无法连接服务器，请检查服务器是否启动。");
                registeredBut.requestFocus();
                return;
            }
            showRegisterRejection(e.getMessage());
        }).withFinal(() -> {
            centerPane.setDisable(false);
            registeredBut.setText("注册");
        }).run();
    }

    /**
     * 用户名输入结束（离开输入框）后询问是否占用。格式还不合法时不发请求。
     */
    private void checkUsernameAvailable() {
        final String name = reUserNameTextField.getText() == null ? "" : reUserNameTextField.getText().trim();
        if (!UserVisibleError.readyToCheckUsername(name)) {
            return;
        }
        final int generation = usernameCheckGeneration.get();
        ProcessChain.create().addSupplierInExecutor(() -> {
            ObjectRestResponse<Boolean> rel = Request.connector(RegisterFeign.class).available(name);
            return rel != null && Boolean.FALSE.equals(rel.getData());
        }).addConsumerInPlatformThread(taken -> {
            if (generation != usernameCheckGeneration.get()) {
                return;
            }
            String current = reUserNameTextField.getText() == null ? "" : reUserNameTextField.getText().trim();
            if (!name.equals(current)) {
                return;
            }
            usernameTakenValidator.setTaken(Boolean.TRUE.equals(taken));
            reUserNameTextField.validate();
        }).onException(e -> log.debug("用户名检查没有完成：{}", e.getMessage())).run();
    }

    private boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.ConnectException
                || current instanceof java.net.UnknownHostException
                || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String text = current.getMessage() == null ? "" : current.getMessage();
            if (text.contains("Connection refused") || text.contains("connect timed out")
                || text.contains("UnknownHost")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void showRegisterRejection(String message) {
        String text = UserVisibleError.humanize(message);
        if (UserVisibleError.USERNAME_TAKEN.equals(text)) {
            usernameCheckGeneration.incrementAndGet();
            usernameTakenValidator.setTaken(true);
            reUserNameTextField.validate();
            errorLabel1.setText("");
            reUserNameTextField.requestFocus();
            return;
        }
        if (text.isEmpty()) {
            text = "注册没有成功，请稍后再试";
        }
        errorLabel1.setText(text);
        registeredBut.requestFocus();
    }

    @ActionMethod("login")
    private void login() {
        final String username = userNameTextField.getText();
        final String rawPassword = passWordTextField.getText();
        // 令牌要绑定设备，设备标识必须在登录请求之前就确定并放进请求头。
        if (username != null && !username.trim().isEmpty()) {
            ApplicatonStore.setDeviceId(
                new com.junesoon.mim.service.DeviceIdentity().getOrCreate(username.trim()));
        }
        JwtAuthenticationRequest request = new JwtAuthenticationRequest(username,
            EncryptUtil.base64Encode(rawPassword));
        runLogin(request, username, rawPassword);
    }

    /**
     * 执行一次登录。服务端返回“需要二次验证码”时，弹窗输入后带 code 重试。
     */
    private void runLogin(JwtAuthenticationRequest jwtAuthenticationRequest, String username,
        String rawPassword) {
        ProcessChain.create().addRunnableInPlatformThread(() -> {
            centerPane.setDisable(true);
            loginBut.setText("正在登录...");
        }).addSupplierInExecutor(() -> {
            ObjectRestResponse<String> rel =
                Request.connector(LoginFeign.class).login(jwtAuthenticationRequest);
            if (rel.getStatus() == 200 && rel.getData() != null) {
                ApplicatonStore.setToken(rel.getData());
                // 本地身份私钥用登录口令加密落盘，会话期间保留口令以便解锁
                ApplicatonStore.setSessionPassword(rawPassword.toCharArray());
                try {
                    KeyManager.getInstance().initialize(username, rawPassword);
                    // 加载本地已保存的双棘轮会话，恢复收发能力
                    MessageService.getInstance().loadSessions(username, rawPassword);
                } catch (IdentityException e) {
                    // 身份初始化失败必须阻断登录：没有可用密钥就无法收发消息，
                    // 静默继续会让用户以为登录成功却发不出任何消息。
                    throw new IllegalStateException(e.getMessage(), e);
                }
                // 预密钥只影响「别人能否离线对我发起会话」，失败不应阻断自己登录
                try {
                    PreKeyManager.getInstance().initialize(username, rawPassword);
                } catch (Exception e) {
                    log.warn("预密钥初始化失败，他人将无法离线发起会话：{}", e.getMessage());
                }
            }
            return rel;
        }).addConsumerInPlatformThread(rel -> {
            if (rel.getStatus() == 200) {
                errorLabel.setText("");
                loadApplicatonStore();
            } else if (rel.getStatus() == STATUS_2FA_REQUIRED) {
                promptTwoFactorCode(jwtAuthenticationRequest, username, rawPassword);
            } else {
                lodingBar.requestFocus();
                errorLabel.setText(rel.getMessage());
            }
        }).onException(e -> {
            e.printStackTrace();
            errorLabel.setText(describeLoginFailure(e));
            lodingBar.requestFocus();
        }).withFinal(() -> {
            centerPane.setDisable(false);
            loginBut.setText("登录");
        }).run();
    }

    private void promptTwoFactorCode(JwtAuthenticationRequest request, String username,
        String rawPassword) {
        javafx.scene.control.TextInputDialog dialog =
            new javafx.scene.control.TextInputDialog();
        dialog.setTitle("二次验证");
        dialog.setHeaderText("该账号已启用二次验证");
        dialog.setContentText("请输入身份验证器中的 6 位验证码：");
        java.util.Optional<String> code = dialog.showAndWait();
        if (!code.isPresent() || code.get().trim().isEmpty()) {
            lodingBar.requestFocus();
            return;
        }
        request.setCode(code.get().trim());
        runLogin(request, username, rawPassword);
    }

    /**
     * 历史同步的设备授权确认。由后台线程调用，必须切到 JavaFX 线程弹窗并阻塞等待。
     *
     * <p>没有用户确认就绝不把明文历史交给另一台设备——这是防止盗号者和
     * 被攻破的服务端静默拉走聊天记录的最后一道门。
     */
    private static boolean askHistorySyncApproval(String deviceId, String fingerprint) {
        java.util.concurrent.FutureTask<Boolean> task =
            new java.util.concurrent.FutureTask<>(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.WARNING);
                alert.setTitle("设备请求同步历史");
                alert.setHeaderText("本账号的另一台设备请求与本机同步历史");
                alert.setContentText("设备标识：\n" + deviceId
                    + "\n\n安全码：\n" + fingerprint
                    + "\n\n请与另一台设备的「本机设备安全码」逐字核对。"
                    + "\n只有当你确实刚在这台设备上登录、并确认是本人操作时才点「确认转发」。"
                    + "\n如果这不是你本人操作，请点「拒绝」。");
                javafx.scene.control.ButtonType accept =
                    new javafx.scene.control.ButtonType("确认转发",
                        javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                javafx.scene.control.ButtonType reject =
                    new javafx.scene.control.ButtonType("拒绝",
                        javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(accept, reject);
                return alert.showAndWait().orElse(reject) == accept;
            });
        if (javafx.application.Platform.isFxApplicationThread()) {
            task.run();
        } else {
            javafx.application.Platform.runLater(task);
        }
        try {
            return task.get(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("等待历史同步授权失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 把加密身份初始化失败与网络失败区分开，给出可操作的提示。
     */
    private String describeLoginFailure(Throwable e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (message.contains("解锁失败")) {
            return "本机身份密钥无法解锁：登录口令可能已变更。删除 ~/.mim/identity 下的文件可以重新生成密钥，但历史消息将无法解密。";
        }
        if (message.contains("同步公钥")) {
            return "登录成功，但公钥同步失败：" + message;
        }
        return "无法连接服务器，请检查服务器是否启动。";
    }

    public void loadApplicatonStore() {
        ProcessChain.create().addRunnableInPlatformThread(() -> {
            try {
                actionHandler.navigate(LoadingController.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
            // 只重置视图状态：token 与口令刚在 login() 里建立，
            // 若在这里 clearUserInfo() 会把它们清掉，导致后续请求 401。
            ApplicatonStore.resetSessionView();
        }).addSupplierInExecutor(() ->
            Request.connector(LoginFeign.class).getInfo()
        ).addConsumerInPlatformThread(rel -> {
            if (rel.getStatus() == 200) {
                UserInfo frontUser = rel.getData();
                ApplicatonStore.setUserInfo(frontUser);
            } else {
                AlertUtil.show(rel);
            }
        }).addSupplierInExecutor(() ->
            Request.connector(LoginFeign.class).getChatContacts()
        ).addConsumerInPlatformThread(rel -> {
            ApplicatonStore.getChatContacts().addAll(rel);
        }).addRunnableInExecutor(() -> {
            // 凭证就绪后再建立长连接，握手拦截器需要 Authorization 头。
            // 先挂上历史同步监听，避免其他设备的补发在监听注册前到达。
            HistorySync.setApprover(LoginController::askHistorySyncApproval);
            MessageService.setSyncApprover(LoginController::askHistorySyncApproval);
            HistorySync.arm();
            WSClient.getInstance().connectWithToken();
            HistorySync.requestInBackground();
        }).addConsumerInPlatformThread(rel -> {
            try {
                actionHandler.navigate(MainController.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).onException(e -> {
            e.printStackTrace();
            try {
                actionHandler.navigate(LoginController.class);
            } catch (Exception flowException) {
                flowException.printStackTrace();
            }
        }).run();
    }

    @PreDestroy
    private void destroy() {
        sequentialTransition.stop();
    }

}


