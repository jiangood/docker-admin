package io.github.jiangood.docker.admin.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Manifest;

/**
 * 平台自身版本号。
 * <p>
 * agent 镜像与平台<b>同 tag 发版</b>（见 {@code .github/workflows/publish-docker-image.yml}），
 * 因此平台自身的版本就是最新 agent 镜像的版本，{@code HostAgentService} 拿它
 * 与运行中 agent 镜像内置的版本 label 比较，即可判断该主机的 agent 是否落后。
 * <p>
 * 版本来源：spring-boot-maven-plugin 打 fat jar 时默认把项目版本写进
 * {@code META-INF/MANIFEST.MF} 的 {@code Implementation-Version}。
 * 本地 IDE 直接跑（未打包）时读不到，返回 null，调用方据此跳过比较、不告警。
 */
@Slf4j
public final class PlatformVersion {

    private static volatile String cached;

    private PlatformVersion() {
    }

    /**
     * 平台自身版本（如 4.8.88），读取失败返回 null。
     */
    public static String get() {
        String value = cached;
        if (value == null) {
            synchronized (PlatformVersion.class) {
                if (cached == null) {
                    cached = readVersion();
                    if (cached == null) {
                        log.debug("未能读取平台版本号（未打包运行或 manifest 缺失），Agent 版本比较将跳过");
                    }
                }
                value = cached;
            }
        }
        return value;
    }

    private static String readVersion() {
        // Spring Boot 可执行 jar 的类加载器无法把 manifest 属性注入 Package（LaunchedURLClassLoader
        // 不读嵌套 jar 的属性），所以直接读 classpath 根上的 MANIFEST.MF 并解析
        InputStream in = PlatformVersion.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF");
        if (in == null) {
            return null;
        }
        try (in) {
            String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
            return version == null || version.isBlank() ? null : version.trim();
        } catch (IOException e) {
            log.debug("读取平台版本号失败: {}", e.getMessage());
            return null;
        }
    }
}
