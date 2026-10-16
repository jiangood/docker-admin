package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 镜像构建时间解析：注册中心读到的 image config 与本地 {@code docker inspect} 返回的时间字符串格式一致，统一在这里解析。
 * <p>
 * OCI/Docker 镜像配置里的 {@code created} 是 RFC3339
 * （如 {@code 2026-10-10T01:35:06.803968147Z}，纳秒精度），
 * 部分注册中心接口返回毫秒时间戳字符串，两种都兜住，都不识别时返回 {@code null}。
 */
public final class ImageTimeUtils {

    private ImageTimeUtils() {
    }

    public static LocalDateTime parse(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 不是 RFC3339，继续按时间戳试
        }
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(v)), ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // 也不是时间戳
            }
        }
        return null;
    }
}
