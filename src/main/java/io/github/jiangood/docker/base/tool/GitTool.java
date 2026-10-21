package io.github.jiangood.docker.base.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.base.FileLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.IdentityPasswordProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GitTool {

    private static final Logger fileLog = FileLog.logger();

    @AllArgsConstructor
    @Data
    public static class CloneResult {
        File dir;
        String codeMessage;

        LocalDateTime commitTime;

        /** 克隆到的提交短 id（8 位）；分支构建据此生成镜像 tag。 */
        String commitId;
    }

    /** 克隆目录清理结果。 */
    @AllArgsConstructor
    @Data
    public static class CleanupResult {
        /** 删除的目录数。 */
        int deletedDirs;

        /** 回收的字节数。 */
        long releasedBytes;
    }

    /** GitLab 等平台使用访问令牌时，用户名可填任意非空值，统一使用 oauth2。 */
    private static final String TOKEN_USERNAME = "oauth2";

    /** 平台写入仓库时的提交人身份；未配置 git user.name/email 时避免提交失败。 */
    private static final String COMMITTER_NAME = "docker-admin";
    private static final String COMMITTER_EMAIL = "docker-admin@localhost";

    /** 未指定提交说明时的默认说明。 */
    private static final String DEFAULT_COMMIT_MESSAGE = "chore: 更新 Dockerfile";

    /**
     * 代码克隆根目录，每个构建在其下使用固定目录 {@code <项目名>}（见 {@link #cloneDir(String)}）。
     * <p>
     * 放在系统临时目录（容器内即 /tmp），不占用持久化的 /data：构建结束即删除目录，
     * 容器重建时残留也随之消失；进程异常退出留下的目录由 {@link #cleanupCloneDirs()} 兜底清理。
     */
    public static final String CLONE_BASE_DIR = new File(FileUtil.getTmpDir(), "gitcode").getAbsolutePath();

    /** 残留代码目录的最长保留天数；正常构建结束会立即删除，这里只兜底异常退出留下的目录。 */
    private static final int RETAIN_DAYS = 10;

    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * 接受任意 SSH 主机密钥：代码源可配置的是私钥而非 known_hosts，
     * 因此只做信任并告警，不做主机密钥校验。
     */
    private static final ServerKeyDatabase ACCEPT_ANY_HOST_KEY = new ServerKeyDatabase() {
        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
            return Collections.emptyList();
        }

        @Override
        public boolean accept(String connectAddress, InetSocketAddress remoteAddress, PublicKey serverKey,
                Configuration config, org.eclipse.jgit.transport.CredentialsProvider provider) {
            fileLog.warn("未校验 SSH 主机密钥，直接信任 {}：{}", connectAddress, KeyUtils.getFingerPrint(serverKey));
            return true;
        }
    };

    /**
     * 列出远程仓库的所有 tag（不含分支）。
     */
    public static List<String> listRemoteTags(String url, GitCredential credential) throws GitAPIException {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(url)
                .setHeads(false)
                .setTags(true);

        SshdSessionFactory sshFactory = applyCredential(cmd, url, credential);
        try {
            Collection<Ref> refs = cmd.call();
            List<String> tags = new ArrayList<>();
            for (Ref ref : refs) {
                String name = ref.getName();
                if (name.startsWith("refs/tags/")) {
                    tags.add(name.substring("refs/tags/".length()));
                }
            }
            // 顺序由调用方按版本规则处理（见 VersionUtils），这里不排序
            return tags;
        } finally {
            closeQuietly(sshFactory);
        }
    }

    /**
     * 列出远程仓库的所有分支（不含 tag）。
     */
    public static List<String> listRemoteBranches(String url, GitCredential credential) throws GitAPIException {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(url)
                .setHeads(true)
                .setTags(false);

        SshdSessionFactory sshFactory = applyCredential(cmd, url, credential);
        try {
            Collection<Ref> refs = cmd.call();
            List<String> branches = new ArrayList<>();
            for (Ref ref : refs) {
                String name = ref.getName();
                if (name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                }
            }
            // 顺序由调用方处理（main/master 优先），这里不排序
            return branches;
        } finally {
            closeQuietly(sshFactory);
        }
    }

    /**
     * 轻量读取远程仓库中的单个文本文件：浅克隆（depth=1，不拉取子模块）到临时目录，读完即删除。
     *
     * @return 文件内容；仓库中不存在该文件时返回 null
     */
    public static String readTextFile(String url, GitCredential credential, String path) throws GitAPIException {
        File workDir = new File(FileUtil.getTmpDir(), "gitcode-read/" + IdUtil.fastSimpleUUID());
        FileUtil.mkdir(workDir);

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(url)
                .setDirectory(workDir)
                .setCloneSubmodules(false)
                .setDepth(1);

        SshdSessionFactory sshFactory = applyCredential(cloneCommand, url, credential);
        try {
            try (Git git = cloneCommand.call()) {
                File file = new File(workDir, path);
                if (!file.isFile()) {
                    return null;
                }
                return FileUtil.readUtf8String(file);
            }
        } finally {
            closeQuietly(sshFactory);
            FileUtil.del(workDir);
        }
    }

    /**
     * 将文本写入远程仓库中的指定文件，提交并推送（文件不存在则新建）。
     * <p>
     * 使用完整克隆而非浅克隆，避免 JGit 从浅仓库推送时的兼容问题。
     *
     * @param message 提交说明，为空时使用默认说明
     * @param branch  目标分支；为空时使用仓库默认分支，非空时克隆该分支并推回
     * @return 新提交的短 id；内容与仓库一致、无需提交时返回 null
     */
    public static String writeTextFile(String url, GitCredential credential, String path, String content, String message,
            String branch) throws GitAPIException, IOException {
        File workDir = new File(FileUtil.getTmpDir(), "gitcode-write/" + IdUtil.fastSimpleUUID());
        FileUtil.mkdir(workDir);

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(url)
                .setDirectory(workDir)
                .setBranch(StrUtil.trimToNull(branch))
                .setCloneSubmodules(false);

        SshdSessionFactory sshFactory = applyCredential(cloneCommand, url, credential);
        try (Git git = cloneCommand.call()) {
            String targetBranch = git.getRepository().getBranch();

            File file = new File(workDir, path);
            FileUtil.mkParentDirs(file);
            FileUtil.writeUtf8String(content, file);

            if (git.status().call().isClean()) {
                fileLog.info("{} 内容与仓库一致，无需提交", path);
                return null;
            }

            git.add().addFilepattern(path).call();

            PersonIdent identity = new PersonIdent(COMMITTER_NAME, COMMITTER_EMAIL);
            RevCommit commit = git.commit()
                    .setMessage(StrUtil.blankToDefault(message, DEFAULT_COMMIT_MESSAGE))
                    .setAuthor(identity)
                    .setCommitter(identity)
                    .call();
            fileLog.info("已提交 {}：{}", commit.getName(), commit.getShortMessage());

            PushCommand pushCommand = git.push()
                    .setRemote("origin")
                    .add("refs/heads/" + targetBranch + ":refs/heads/" + targetBranch);
            // 复用克隆时的 SSH 会话工厂；令牌 / 账号密码方式此调用返回 null
            applyCredential(pushCommand, url, credential, sshFactory);
            assertPushed(pushCommand.call());

            fileLog.info("已推送到 {} 分支 {}", url, targetBranch);
            return commit.getName().substring(0, 8);
        } finally {
            closeQuietly(sshFactory);
            FileUtil.del(workDir);
        }
    }

    /**
     * 项目固定的代码目录：{@code <临时目录>/gitcode/<项目名>}。
     * <p>
     * 用项目名而非 id，便于直接在磁盘上认出是哪个项目（项目名在 {@code Project#name} 上唯一）。
     * 名称里的路径分隔符、文件系统非法字符与空白统一替换为下划线，避免生成畸形路径。
     */
    public static File cloneDir(String projectName) {
        return new File(CLONE_BASE_DIR, sanitizeDirName(projectName));
    }

    static String sanitizeDirName(String projectName) {
        String name = StrUtil.blankToDefault(projectName, "project").trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        // 防止 "." / ".." 之类名称把目录指到克隆根目录之外
        if (name.replace(".", "").isEmpty()) {
            return "project";
        }
        return name;
    }

    public static CloneResult clone(String projectName, String url, GitCredential credential, String branchOrTag)
            throws GitAPIException {

        // 固定目录会被复用，克隆前先清空：换分支/tag 后残留的旧文件会让构建上下文不干净
        File workDir = cloneDir(projectName);
        if (workDir.exists()) {
            long size = FileUtil.size(workDir);
            if (!FileUtil.del(workDir)) {
                throw new IllegalStateException("清理代码目录失败，请手动删除后重试：" + workDir.getAbsolutePath());
            }
            fileLog.info("已清空上次的代码目录 {}（{}）", workDir.getAbsolutePath(), FileUtil.readableFileSize(size));
        }
        FileUtil.mkdir(workDir);

        long start = System.currentTimeMillis();


        fileLog.info("工作目录为 {}", workDir.getAbsolutePath());
        fileLog.info("克隆代码 {}", url);

        CloneCommand cloneCommand = Git.cloneRepository()
                .setCloneSubmodules(true)
                .setURI(url)
                .setDirectory(workDir)
                .setBranch(branchOrTag)
                .setProgressMonitor(new TextProgressMonitor())
                ;

        SshdSessionFactory sshFactory = applyCredential(cloneCommand, url, credential);
        try {
            Git git = cloneCommand.call();

            LogCommand logcmd = git.log();
            Iterable<RevCommit> logResult = logcmd.call();
            RevCommit next = logResult.iterator().next();

            String submitMessage = next.getFullMessage();
            fileLog.info("代码日志：{}", submitMessage);

            String commitId = next.getName().substring(0, 8);

            git.close();
            fileLog.info("代码获取完毕, 共 {} M", FileUtil.readableFileSize(FileUtils.sizeOfDirectory(workDir)));

            fileLog.info("耗时：{} 秒", (System.currentTimeMillis() - start) / 1000);


            return new CloneResult(workDir, submitMessage.trim(), LocalDateTime.ofInstant(Instant.ofEpochMilli(next.getCommitTime() * 1000L), ZoneId.systemDefault()), commitId);
        } finally {
            closeQuietly(sshFactory);
        }
    }

    /**
     * 删除构建用的代码目录。
     *
     * @return true 表示目录已不存在或删除成功
     */
    public static boolean deleteCloneDir(File workDir) {
        if (workDir == null || !workDir.exists()) {
            return true;
        }
        return FileUtil.del(workDir);
    }

    /**
     * 全量检查一次克隆根目录 {@link #CLONE_BASE_DIR}，清理最后修改时间超过保留天数
     * （{@link #RETAIN_DAYS}）的残留目录。
     * <p>
     * 每次构建结束时调用：构建自己的目录已被删除，这里处理的是进程被强杀、容器异常退出
     * 等没走到删除逻辑留下的目录，以及改名/删除项目后遗留的旧目录。刚创建的目录（正在构建）
     * 远未超过保留天数，不会被误删。
     */
    public static CleanupResult cleanupCloneDirs() {
        try {
            long expireTime = System.currentTimeMillis() - RETAIN_DAYS * ONE_DAY_MILLIS;
            return cleanupCloneDirs(new File(CLONE_BASE_DIR), expireTime);
        } catch (Exception e) {
            fileLog.warn("清理残留代码目录失败：{}", e.getMessage(), e);
            return new CleanupResult(0, 0);
        }
    }

    /** 删除最后修改时间早于 {@code expireTime} 的目录；包级可见便于测试。 */
    static CleanupResult cleanupCloneDirs(File baseDir, long expireTime) {
        File[] dirs = baseDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return new CleanupResult(0, 0);
        }

        int deletedDirs = 0;
        long releasedBytes = 0;
        for (File dir : dirs) {
            if (dir.lastModified() >= expireTime) {
                continue;
            }
            long size = FileUtil.size(dir);
            if (FileUtil.del(dir)) {
                deletedDirs++;
                releasedBytes += size;
                fileLog.info("已清理残留代码目录 {}（{}）", dir.getAbsolutePath(), FileUtil.readableFileSize(size));
            } else {
                fileLog.warn("清理残留代码目录失败 {}", dir.getAbsolutePath());
            }
        }

        if (deletedDirs > 0) {
            fileLog.info("代码目录清理完毕，共删除 {} 个目录，回收 {}", deletedDirs, FileUtil.readableFileSize(releasedBytes));
        }
        return new CleanupResult(deletedDirs, releasedBytes);
    }

    /**
     * 按访问方式给命令设置凭据。
     *
     * @return SSH 私钥方式返回会话工厂，由调用方在使用完毕后关闭；其他方式返回 null
     */
    private static SshdSessionFactory applyCredential(TransportCommand<?, ?> command, String url, GitCredential credential) {
        return applyCredential(command, url, credential, null);
    }

    /**
     * 同上，可复用已有的 SSH 会话工厂（如同一仓库先克隆后推送），避免重复创建与清理。
     *
     * @param existing SSH 会话工厂，为 null 时按需新建
     * @return 实际使用的 SSH 会话工厂；非 SSH 方式返回 null
     */
    private static SshdSessionFactory applyCredential(TransportCommand<?, ?> command, String url, GitCredential credential,
            SshdSessionFactory existing) {
        if (credential == null || credential.isNone()) {
            // 无凭据：按公开仓库匿名访问
            return null;
        }

        if (credential.getKind() == GitCredential.Kind.SSH_KEY) {
            if (!isSshUrl(url)) {
                throw new IllegalArgumentException("SSH 私钥方式需要 ssh:// 或 git@host:path 形式的仓库地址：" + url);
            }
            SshdSessionFactory factory = existing != null ? existing : createSshSessionFactory(credential);
            command.setTransportConfigCallback(transport -> {
                if (transport instanceof SshTransport sshTransport) {
                    sshTransport.setSshSessionFactory(factory);
                }
            });
            return factory;
        }

        // 账号密码 / 访问令牌：忽略 URL 里可能存在的用户名，统一用配置的用户名
        String username = StrUtil.isBlank(credential.getUsername())
                ? (credential.getKind() == GitCredential.Kind.TOKEN ? TOKEN_USERNAME : "")
                : credential.getUsername().trim();
        command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, credential.getSecret()));
        return null;
    }

    /**
     * 校验推送结果：存在被拒绝的引用（如非快进、无权限）时抛出异常。
     */
    private static void assertPushed(Iterable<PushResult> results) {
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new IllegalStateException("推送被拒绝：" + status
                            + (StrUtil.isBlank(update.getMessage()) ? "" : "（" + update.getMessage() + "）"));
                }
            }
        }
    }

    /**
     * 用配置的私钥文本构造 SSH 会话工厂；私钥落在会话级临时目录，随工厂一起清理。
     */
    private static SshdSessionFactory createSshSessionFactory(GitCredential credential) {
        File sshDir = new File(FileUtil.getTmpDir(), "code-source-ssh/" + IdUtil.fastSimpleUUID());
        FileUtil.mkdir(sshDir);
        File keyFile = new File(sshDir, "id_key");
        FileUtil.writeUtf8String(credential.getPrivateKey(), keyFile);

        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setHomeDirectory(sshDir)
                .setSshDirectory(sshDir)
                .setPreferredAuthentications("publickey")
                .setDefaultIdentities(dir -> Collections.singletonList(keyFile.toPath()))
                .setServerKeyDatabase((home, dir) -> ACCEPT_ANY_HOST_KEY);

        if (StrUtil.isNotBlank(credential.getPassphrase())) {
            // 加密私钥：用凭据提供者回答解密口令
            builder.setKeyPasswordProvider(ignored -> new IdentityPasswordProvider(
                    new UsernamePasswordCredentialsProvider("", credential.getPassphrase())));
        }
        return builder.build(null);
    }

    private static boolean isSshUrl(String url) {
        String value = StrUtil.trimToEmpty(url);
        return value.startsWith("ssh://") || (!value.contains("://") && value.contains("@") && value.contains(":"));
    }

    private static void closeQuietly(SshdSessionFactory factory) {
        if (factory == null) {
            return;
        }
        File sshDir = factory.getSshDirectory();
        try {
            factory.close();
        } catch (Exception e) {
            fileLog.warn("关闭 SSH 会话工厂失败：{}", e.getMessage());
        }
        if (sshDir != null) {
            FileUtil.del(sshDir);
        }
    }
}
