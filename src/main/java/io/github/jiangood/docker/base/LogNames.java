package io.github.jiangood.docker.base;

/**
 * 日志名常量。
 * <p>
 * 需要在前端日志弹窗（构建、部署、构建测试、镜像同步/导出）里实时展示的日志，
 * 统一使用 {@link #TASK} 这一个固定 logger 名：logback 只按这个名字把日志
 * 按 MDC 中的 {@code logFileId} 分流到 {@code ${LOG_PATH}/{logId}.log}。
 * <p>
 * 这里刻意用固定的名字而不是包名/类名——每个任务日志文件内并不打印 logger 名，
 * 任务之间的区分完全靠 MDC，因此无需按类区分；固定名字可以避免后续调整包结构时日志丢失。
 */
public final class LogNames {

    /** 任务日志统一使用的 logger 名，与 logback-spring.xml 中的 {@code <logger name="task-log">} 对应。 */
    public static final String TASK = "task-log";

    private LogNames() {
    }
}
