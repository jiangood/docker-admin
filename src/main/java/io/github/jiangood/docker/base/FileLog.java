package io.github.jiangood.docker.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 文件日志：写入 {@code ${logging.file.path}/{logId}.log} 的日志，供前端日志弹窗实时查看
 * （构建、部署、构建测试、镜像同步/导出）。
 * <p>
 * 全局统一入口，写入哪个文件只取决于当前线程绑定的 {@code logId}，与包名/类名无关：
 * <ul>
 *   <li>{@link #logger()}：取文件日志 logger（固定名 {@link #LOGGER_NAME}）</li>
 *   <li>{@link #bind(String)} / {@link #unbind()} / {@link #currentLogId()}：绑定 / 解绑当前线程的日志文件</li>
 * </ul>
 * <p>
 * 与 {@code logback-spring.xml} 的对应关系：{@code <logger name="file-log">} 接上按
 * {@link #MDC_KEY} 分流的 {@code SiftingAppender}。改这里的常量时，logback 里的两个字面量要同步改。
 */
public final class FileLog {

    /** 文件日志统一使用的 logger 名，与 logback-spring.xml 中的同名 logger 对应。 */
    public static final String LOGGER_NAME = "file-log";

    /** 绑定 logId 的 MDC key，须与 logback-spring.xml 中 discriminator 的 key 一致。 */
    public static final String MDC_KEY = "logFileId";

    private FileLog() {
    }

    /**
     * 文件日志 logger。任务线程内（已 {@link #bind(String)}）的日志会写入对应任务的日志文件，
     * 任务之外只进控制台 / {@code spring.log}。
     */
    public static Logger logger() {
        return LoggerFactory.getLogger(LOGGER_NAME);
    }

    /**
     * 把当前线程绑定到 {@code logId} 对应的日志文件。
     *
     * @param logId 日志标识，即日志文件名（不含 .log），如构建日志 id、应用（部署）id
     */
    public static void bind(String logId) {
        MDC.put(MDC_KEY, logId);
    }

    /** 解绑当前线程的日志文件，避免线程复用（如 @Async 线程池）时串日志。 */
    public static void unbind() {
        MDC.remove(MDC_KEY);
    }

    /** 当前线程绑定的 logId；未绑定时返回 null。 */
    public static String currentLogId() {
        return MDC.get(MDC_KEY);
    }
}
