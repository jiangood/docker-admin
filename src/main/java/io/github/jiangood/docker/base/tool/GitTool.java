package io.github.jiangood.docker.base.tool;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.IdentityPasswordProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import java.io.File;
import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
public class GitTool {

    @AllArgsConstructor
    @Data
    public static class CloneResult {
        File dir;
        String codeMessage;

        LocalDateTime commitTime;
    }

    /** GitLab 等平台使用访问令牌时，用户名可填任意非空值，统一使用 oauth2。 */
    private static final String TOKEN_USERNAME = "oauth2";

    /**
     * 代码克隆根目录，每次克隆在其下按 仓库名/时间戳 新建工作目录。
     * 目录不会随构建结束自动删除，由 {@code CleanGitCodeJob} 定时清理。
     */
    public static final String CLONE_BASE_DIR = "/data/gitcode";

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
            log.warn("未校验 SSH 主机密钥，直接信任 {}：{}", connectAddress, KeyUtils.getFingerPrint(serverKey));
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
            Collections.sort(tags);
            return tags;
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

    public static CloneResult clone(String url, GitCredential credential, String branchOrTag) throws GitAPIException {

        String dirName = url.substring(url.lastIndexOf("/") + 1);
        dirName = dirName.replace(".git", "");

        File workDir = new File(CLONE_BASE_DIR, dirName + "/" + DateUtil.date().toString("yyyyMMddHHmmss"));

        long start = System.currentTimeMillis();


        log.info("工作目录为 {}", workDir.getAbsolutePath());
        log.info("克隆代码 {}", url);

        FileUtil.del(workDir);


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
            log.info("代码日志：{}", submitMessage);


            git.close();
            log.info("代码获取完毕, 共 {} M", FileUtil.readableFileSize(FileUtils.sizeOfDirectory(workDir)));

            log.info("耗时：{} 秒", (System.currentTimeMillis() - start) / 1000);


            return new CloneResult(workDir, submitMessage.trim(), LocalDateTime.ofInstant(Instant.ofEpochMilli(next.getCommitTime() * 1000L), ZoneId.systemDefault()));
        } finally {
            closeQuietly(sshFactory);
        }
    }

    /**
     * 按访问方式给命令设置凭据。
     *
     * @return SSH 私钥方式返回会话工厂，由调用方在使用完毕后关闭；其他方式返回 null
     */
    private static SshdSessionFactory applyCredential(TransportCommand<?, ?> command, String url, GitCredential credential) {
        if (credential == null || credential.isNone()) {
            // 无凭据：按公开仓库匿名访问
            return null;
        }

        if (credential.getKind() == GitCredential.Kind.SSH_KEY) {
            if (!isSshUrl(url)) {
                throw new IllegalArgumentException("SSH 私钥方式需要 ssh:// 或 git@host:path 形式的仓库地址：" + url);
            }
            SshdSessionFactory factory = createSshSessionFactory(credential);
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
            log.warn("关闭 SSH 会话工厂失败：{}", e.getMessage());
        }
        if (sshDir != null) {
            FileUtil.del(sshDir);
        }
    }
}
