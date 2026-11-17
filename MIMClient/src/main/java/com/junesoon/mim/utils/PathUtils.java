package com.junesoon.mim.utils;

import java.io.File;

public class PathUtils {

    /**
     * 如果不是 jar包，会获取到 /target/classes 目录 因此可以通过此判断是否是 jar 还是开发
     *
     * @return
     */
    public static String getJarPath() {
        return PathUtils.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    }

    /**
     * 判断是不是从 Jar 包读取的情况 如果是，则该路径下的不是文件夹
     *
     * @return
     */
    public static boolean isJar() {
        return !(new File(getJarPath()).isDirectory());
    }
}


