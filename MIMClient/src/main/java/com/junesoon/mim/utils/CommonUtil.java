package com.junesoon.mim.utils;

import javafx.scene.effect.PerspectiveTransform;

/**
 * @program: MIMClient
 * @description: 公共工具类
 * @version: 1.0
 * @create: 2024-03-06 15:40
 */
public class CommonUtil {

    public static void setPT(PerspectiveTransform pt, double t) {
        double width = 650;
        double height = 450;
        double radius = width / 2;
        double back = height / 10;
        pt.setUlx(radius - Math.sin(t) * radius);
        pt.setUly(0 - Math.cos(t) * back);
        pt.setUrx(radius + Math.sin(t) * radius);
        pt.setUry(0 + Math.cos(t) * back);
        pt.setLrx(radius + Math.sin(t) * radius);
        pt.setLry(height - Math.cos(t) * back);
        pt.setLlx(radius - Math.sin(t) * radius);
        pt.setLly(height + Math.cos(t) * back);
    }

}
