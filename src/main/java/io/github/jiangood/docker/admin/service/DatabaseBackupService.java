package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.JakartaMail;
import cn.hutool.extra.mail.MailAccount;
import io.github.jiangood.docker.admin.dao.ProxyRepository;
import io.github.jiangood.docker.admin.entity.Proxy;
import io.github.jiangood.openadmin.framework.proxy.ProxySettings;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import io.github.jiangood.openadmin.modules.mail.MailAttachment;
import io.github.jiangood.openadmin.modules.mail.MailConfigKeys;
import io.github.jiangood.openadmin.modules.mail.MailMessage;
import io.github.jiangood.openadmin.modules.mail.MailProvider;
import io.github.jiangood.openadmin.modules.mail.MailProxyProperties;
import io.github.jiangood.openadmin.modules.mail.service.MailService;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.activation.FileDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * 数据库备份：只导出业务关键表，统一生成 SQL 并 gzip 压缩，发送到
 * 「系统参数 - 邮件发送」里的默认收件邮箱。
 * <p>
 * 不备份日志、任务历史等大表；固定清单见 {@link #DEFAULT_TABLES}。
 * 备份先落到 {@code sys.data-dir/db-backup/} 保留本地副本，再发送邮件；
 * 邮件发送失败不会删除本地文件，可在任务日志里看到文件路径。
 * <ul>
 *   <li>H2：用 {@code SCRIPT DROP ... TABLE ...} 生成建表 + 数据 SQL；</li>
 *   <li>MySQL / MariaDB：用 {@code SHOW CREATE TABLE} + 逐行 {@code INSERT} 生成 SQL
 *       （容器镜像里没有 mysqldump，不能依赖宿主机工具）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseBackupService {

    /** 备份的关键表：业务配置 + 账号权限 + 基础配置，不含任何日志 / 历史表。 */
    public static final List<String> DEFAULT_TABLES = List.of(
            "t_project", "t_app", "t_image", "t_image_tag", "t_host", "t_host_agent_config",
            "t_registry", "t_code_source", "t_proxy", "t_site_monitor",
            "sys_user", "sys_org", "sys_role", "sys_user_role", "sys_user_data_perm",
            "sys_dict_type", "sys_dict_item", "sys_job", "sys_config");

    /** 收件人取框架的默认收件邮箱；未配置时直接失败，避免静默丢备份。 */
    private static final String CONFIG_DEFAULT_TO = MailConfigKeys.DEFAULT_TO;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 常见 SMTP 服务商附件上限约 25~50 MB，超过时仍然尝试发送，但日志与正文提示可能被拒收。 */
    private static final long ATTACHMENT_WARN_BYTES = 20L * 1024 * 1024;

    private final DataSource dataSource;
    private final MailService mailService;
    private final SysConfigService sysConfigService;
    private final ProxyRepository proxyRepository;

    /** 备份临时文件优先放在数据目录（容器里通常是挂载卷），空间不够时回落到系统临时目录。 */
    @Value("${logging.file.path:}")
    private String logPath;

    /**
     * 生成备份、保留本地副本并同步发送邮件，返回任务日志里展示的摘要。
     *
     * @param proxyId 可选的邮件发送代理 id，空表示沿用框架邮件配置
     * @throws BusinessException 备份或发送失败，消息可直接展示给用户
     */
    public String backupAndSend(String proxyId) {
        if (!mailService.isConfigured()) {
            throw new BusinessException("未配置邮件发送服务，已跳过数据库备份。请到「系统管理 - 系统参数 - 邮件发送」填写发件账号");
        }
        List<String> recipients = defaultRecipients();
        if (recipients.isEmpty()) {
            throw new BusinessException("未配置默认收件邮箱，已跳过数据库备份。请到「系统管理 - 系统参数 - 邮件发送」填写默认收件邮箱");
        }

        String ts = LocalDateTime.now().format(FILE_TS);
        LocalDateTime now = LocalDateTime.now();
        BackupFile backup;
        try {
            Path backupDir = prepareBackupDir();
            try (Connection connection = dataSource.getConnection()) {
                backup = createBackup(connection, backupDir, ts);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("数据库备份失败", e);
            throw new BusinessException("数据库备份失败：" + e.getMessage(), e);
        }

        long size = backup.file().length();
        if (size <= 0) {
            throw new BusinessException("数据库备份文件为空：" + backup.file().getAbsolutePath());
        }

        String sizeText = FileUtil.readableFileSize(size);
        log.info("数据库重要表备份已保存到 {}（{}，共 {} 张表），准备发送到 {}",
                backup.file().getAbsolutePath(), sizeText, backup.tables().size(), recipients);

        try {
            sendMail(MailMessage.builder()
                    .to(recipients)
                    .subject("[容器管理平台] 重要表备份 - " + LocalDate.now())
                    .attachment(MailAttachment.of(backup.file().getName(), backup.file()))
                    .html(mailHtml(backup, size, now, recipients))
                    .build(), proxyId);
        } catch (Exception e) {
            log.warn("数据库备份邮件发送失败，本地文件保留：{}", backup.file().getAbsolutePath(), e);
            throw new BusinessException("备份已保存到 " + backup.file().getAbsolutePath()
                    + "，但邮件发送失败：" + e.getMessage(), e);
        }

        return "重要表备份 " + sizeText + "（" + backup.tables().size() + " 张表）已保存到 "
                + backup.file().getAbsolutePath() + "，并发送到 " + String.join("、", recipients);
    }

    /** 本地备份目录：{@code sys.data-dir/db-backup}（容器部署即 /data/db-backup）。 */
    private Path prepareBackupDir() throws IOException {
        File base = StrUtil.isBlank(logPath)
                ? new File("/data")
                : new File(logPath).getAbsoluteFile().getParentFile();
        if (base == null) {
            base = new File(".");
        }
        Path dir = new File(base, "db-backup").toPath();
        Files.createDirectories(dir);
        return dir;
    }

    /** 未选择代理时走框架 MailService（沿用全局邮件代理配置）；选择了代理则用代理直发。 */
    private void sendMail(MailMessage message, String proxyId) {
        if (StrUtil.isBlank(proxyId)) {
            mailService.send(message);
            return;
        }
        Proxy proxy = proxyRepository.findById(proxyId)
                .orElseThrow(() -> new BusinessException("选择的邮件发送代理不存在，请重新选择：" + proxyId));
        sendWithProxy(message, proxy);
    }

    /**
     * 按框架的邮件配置（服务商 / 发件账号 / 授权码）发信，但把 SMTP 连接改为使用指定代理。
     * 不能直接改全局 {@code email.proxy}，否则会影响其他并发邮件。
     */
    private void sendWithProxy(MailMessage message, Proxy proxy) {
        MailProvider provider = MailProvider.parse(sysConfigService.getString(MailConfigKeys.PROVIDER));
        if (provider == null) {
            throw new BusinessException("未选择邮件服务商，无法通过代理发送邮件");
        }
        String username = sysConfigService.getString(MailConfigKeys.USERNAME);
        if (StrUtil.isBlank(username)) {
            throw new BusinessException("未配置发件邮箱，无法通过代理发送邮件");
        }
        String password = sysConfigService.getString(MailConfigKeys.PASSWORD);
        String fromName = sysConfigService.getString(MailConfigKeys.FROM_NAME);
        String from = StrUtil.isBlank(fromName) ? username : fromName + " <" + username + ">";

        MailAccount account = new MailAccount()
                .setHost(provider.host())
                .setPort(provider.port())
                .setAuth(true)
                .setSslEnable(provider.ssl())
                .setStarttlsEnable(provider.starttls())
                .setFrom(from)
                .setUser(username)
                .setPass(password)
                .setCharset(StandardCharsets.UTF_8)
                .setConnectionTimeout(10_000)
                .setTimeout(20_000)
                .setWriteTimeout(20_000);
        ProxySettings settings = ProxySettings.of(proxy.getType(), proxy.getHost(), proxy.getPort(),
                proxy.getUsername(), proxy.getPassword());
        MailProxyProperties.apply(account, settings);

        JakartaMail mail = JakartaMail.create(account)
                .setTos(message.getTo().toArray(String[]::new))
                .setTitle(message.getSubject())
                .setContent(message.getHtml(), true);
        if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            jakarta.activation.DataSource[] attachments = message.getAttachments().stream()
                    .map(attachment -> (jakarta.activation.DataSource) new FileDataSource(attachment.file()))
                    .toArray(jakarta.activation.DataSource[]::new);
            mail.setAttachments(attachments);
        }
        mail.send();
    }

    private List<String> defaultRecipients() {
        List<String> values = sysConfigService.getList(CONFIG_DEFAULT_TO);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().toList();
    }

    private BackupFile createBackup(Connection connection, Path workDir, String ts) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName();
        String lower = product == null ? "" : product.toLowerCase(Locale.ROOT);
        List<String> tables = existingBackupTables(connection);
        if (tables.isEmpty()) {
            throw new BusinessException("没有找到需要备份的重要表，请检查备份表清单："
                    + String.join(", ", DEFAULT_TABLES));
        }
        if (lower.contains("h2")) {
            return backupH2(connection, workDir, ts, tables);
        }
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return backupMySql(connection, workDir, ts, tables);
        }
        throw new BusinessException("暂不支持自动备份的数据库类型：" + StrUtil.blankToDefault(product, "未知"));
    }

    /** 配置里列出、且当前库里确实存在的表（按配置顺序）。 */
    private List<String> existingBackupTables(Connection connection) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                existing.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return DEFAULT_TABLES.stream()
                .filter(name -> existing.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * H2：{@code SCRIPT DROP ... TABLE ...} 生成建表与数据 SQL，再过滤掉脚本里的
     * {@code CREATE USER} / {@code ALTER USER}（避免把源库 SA 口令带回目标库），最后 gzip。
     */
    private BackupFile backupH2(Connection connection, Path workDir, String ts, List<String> tables) throws Exception {
        String url = connection.getMetaData().getURL();
        if (url == null || !url.startsWith("jdbc:h2:file:")) {
            throw new BusinessException("当前 H2 数据库不是文件模式（" + StrUtil.blankToDefault(url, "未知") + "），无法生成备份文件");
        }

        Path raw = workDir.resolve("database-backup-" + ts + ".sql");
        String path = raw.toAbsolutePath().toString().replace('\\', '/');
        // H2 未加引号的标识符按大写处理，正好对应 information_schema 里的表名
        String tableList = tables.stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        try (Statement statement = connection.createStatement()) {
            statement.execute("SCRIPT DROP TO '" + path.replace("'", "''")
                    + "' CHARSET 'UTF-8' TABLE " + tableList);
        }

        File gz = workDir.resolve("database-backup-" + ts + ".sql.gz").toFile();
        try (BufferedReader reader = Files.newBufferedReader(raw, StandardCharsets.UTF_8);
             GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(gz.toPath()), 64 * 1024);
             Writer writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
            writeHeader(writer, "H2", connection.getCatalog(), tables);
            String line;
            while ((line = reader.readLine()) != null) {
                String upper = line.trim().toUpperCase(Locale.ROOT);
                if (upper.startsWith("CREATE USER ") || upper.startsWith("ALTER USER ")) {
                    continue;
                }
                writer.write(line);
                writer.write('\n');
            }
        } finally {
            Files.deleteIfExists(raw);
        }

        return new BackupFile(gz, "H2", gz.getName(),
                "停止平台后解压得到 .sql，在 H2 控制台执行 RUNSCRIPT FROM '备份文件.sql'（脚本含 DROP TABLE，会覆盖同名表）。",
                tables);
    }

    /**
     * MySQL / MariaDB 逻辑备份：{@code SHOW CREATE TABLE} 拿建表语句，再逐行导出 INSERT。
     * 写盘时直接 gzip，避免大表先落一个未压缩的 SQL 文件。
     */
    private BackupFile backupMySql(Connection connection, Path workDir, String ts, List<String> tables) throws Exception {
        String catalog = connection.getCatalog();
        if (StrUtil.isBlank(catalog)) {
            throw new BusinessException("无法确定 MySQL 当前数据库名，请检查 spring.datasource.url 是否包含库名");
        }

        File gz = workDir.resolve("database-backup-" + ts + ".sql.gz").toFile();
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(gz.toPath()), 64 * 1024);
             Writer writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
            writeHeader(writer, "MySQL / MariaDB", catalog, tables);
            writer.write("SET NAMES utf8mb4;\n");
            writer.write("SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n");
            writer.write("SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0;\n\n");

            for (String table : tables) {
                String quoted = quoteIdentifier(table);
                writer.write("-- ----------------------------\n");
                writer.write("-- " + table + "\n");
                writer.write("-- ----------------------------\n");
                writer.write("DROP TABLE IF EXISTS " + quoted + ";\n");
                writer.write(showCreateTable(connection, table) + ";\n\n");
                writeTableRows(connection, table, writer);
            }

            writer.write("SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS;\n");
            writer.write("SET SQL_MODE=@OLD_SQL_MODE;\n");
        }
        return new BackupFile(gz, "MySQL / MariaDB", gz.getName(),
                "解压得到 .sql 后，先创建同名数据库，再执行 mysql < 备份文件.sql（脚本含 DROP TABLE，会覆盖同名表）。",
                tables);
    }

    private static void writeHeader(Writer writer, String dbType, String catalog, List<String> tables) throws IOException {
        writer.write("-- docker-admin 数据库重要表备份\n");
        writer.write("-- 数据库类型：" + dbType + "\n");
        writer.write("-- 数据库：" + StrUtil.blankToDefault(catalog, "-") + "\n");
        writer.write("-- 备份表（" + tables.size() + "）：" + String.join(", ", tables) + "\n");
        writer.write("-- 生成时间：" + LocalDateTime.now().format(DISPLAY_TS) + "\n\n");
    }

    private String showCreateTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW CREATE TABLE " + quoteIdentifier(table))) {
            if (!rs.next()) {
                throw new BusinessException("读取表结构失败：" + table);
            }
            return rs.getString(2);
        }
    }

    private void writeTableRows(Connection connection, String table, Writer writer) throws Exception {
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // MySQL 驱动约定：Integer.MIN_VALUE 表示逐行流式读取，避免大表一次性进内存
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + quoteIdentifier(table))) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                StringBuilder prefix = new StringBuilder("INSERT INTO ")
                        .append(quoteIdentifier(table))
                        .append(" (");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        prefix.append(", ");
                    }
                    prefix.append(quoteIdentifier(meta.getColumnLabel(i)));
                }
                prefix.append(") VALUES (");

                StringBuilder row = new StringBuilder(prefix.length() + 256);
                while (rs.next()) {
                    row.setLength(0);
                    row.append(prefix);
                    for (int i = 1; i <= columnCount; i++) {
                        if (i > 1) {
                            row.append(", ");
                        }
                        row.append(toSqlLiteral(rs.getObject(i)));
                    }
                    row.append(");\n");
                    writer.write(row.toString());
                }
            }
        }
        writer.write("\n");
    }

    private static String quoteIdentifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String toSqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return hexLiteral(bytes);
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "NULL";
            }
            return value.toString();
        }
        if (value instanceof LocalDateTime dt) {
            return quote(sqlDateTime(dt));
        }
        if (value instanceof java.sql.Timestamp ts) {
            return quote(sqlDateTime(ts.toLocalDateTime()));
        }
        if (value instanceof LocalDate date) {
            return quote(date.toString());
        }
        if (value instanceof LocalTime time) {
            return quote(time.truncatedTo(ChronoUnit.MICROS).toString());
        }
        if (value instanceof java.sql.Date date) {
            return quote(date.toLocalDate().toString());
        }
        if (value instanceof java.sql.Time time) {
            return quote(time.toLocalTime().truncatedTo(ChronoUnit.MICROS).toString());
        }
        if (value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof java.time.Instant) {
            return quote(value.toString());
        }
        if (value instanceof Blob blob) {
            try (InputStream in = blob.getBinaryStream()) {
                return hexLiteral(in.readAllBytes());
            } catch (Exception e) {
                throw new BusinessException("读取二进制字段失败：" + e.getMessage(), e);
            }
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                }
                return quote(text.toString());
            } catch (Exception e) {
                throw new BusinessException("读取大文本字段失败：" + e.getMessage(), e);
            }
        }
        return quote(value.toString());
    }

    /** MySQL 的 datetime 最多 6 位小数，截到微秒；T 换成空格，保留非零小数部分。 */
    private static String sqlDateTime(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.MICROS).toString().replace('T', ' ');
    }

    private static String hexLiteral(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2 + 3).append("X'");
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.append('\'').toString();
    }

    /** MySQL 字符串字面量：单引号双写，反斜杠、换行等按 MySQL 转义规则处理。 */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\0' -> sb.append("\\0");
                case '\032' -> sb.append("\\Z");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    private String mailHtml(BackupFile backup, long size, LocalDateTime now, List<String> recipients) {
        boolean large = size > ATTACHMENT_WARN_BYTES;
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;")
                .append("color:#1f2937;font-size:14px;line-height:1.7\">");
        html.append("<h2 style=\"font-size:18px;margin:0 0 4px;color:#16a34a\">数据库重要表备份完成</h2>");
        html.append("<p style=\"margin:0 0 16px;color:#6b7280\">")
                .append(escape(now.format(DISPLAY_TS)))
                .append("</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;")
                .append("max-width:680px;border:1px solid #e5e7eb\">");
        appendRow(html, "数据库类型", backup.dbType());
        appendRow(html, "备份文件", backup.fileName());
        appendRow(html, "文件大小", FileUtil.readableFileSize(size));
        appendRow(html, "备份表（" + backup.tables().size() + " 张）", String.join("、", backup.tables()));
        appendRow(html, "本地副本", backup.file().getAbsolutePath());
        appendRow(html, "收件人", String.join("、", recipients));
        appendRow(html, "恢复方式", backup.restoreHint());
        html.append("</table>");
        if (large) {
            html.append("<p style=\"margin:16px 0 0;color:#b45309\">附件较大，部分邮件服务商可能拒收；")
                    .append("如未收到，请到「系统管理 - 系统参数 - 邮件发送」检查服务商附件上限。</p>");
        }
        html.append("<p style=\"margin:16px 0 0;color:#4b5563\">备份只包含业务配置与账号权限等关键表，")
                .append("不含日志、任务历史等大表。备份同时保留在平台服务器的 db-backup 目录，")
                .append("邮件未收到或发送失败时可从本地副本取回。</p>");
        html.append("</div>");
        return html.toString();
    }

    private static void appendRow(StringBuilder html, String label, String value) {
        html.append("<tr>")
                .append("<td style=\"width:110px;border:1px solid #e5e7eb;background:#f9fafb;color:#4b5563;white-space:nowrap\">")
                .append(escape(label)).append("</td>")
                .append("<td style=\"border:1px solid #e5e7eb;word-break:break-all\">")
                .append(escape(value)).append("</td>")
                .append("</tr>");
    }

    private static String escape(String value) {
        return StrUtil.isBlank(value) ? "-" : HtmlUtils.htmlEscape(value.trim());
    }

    private record BackupFile(File file, String dbType, String fileName, String restoreHint, List<String> tables) {
    }
}
