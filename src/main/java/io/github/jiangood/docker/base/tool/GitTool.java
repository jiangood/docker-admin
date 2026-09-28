package io.github.jiangood.docker.base.tool;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
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

    /**
     * 列出远程仓库的所有 tag（不含分支）。
     */
    public static List<String> listRemoteTags(String url, String user, String password) throws GitAPIException {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(url)
                .setHeads(false)
                .setTags(true);

        if (user != null && password != null) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, password));
        }

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
    }

    public static CloneResult clone(String url, String user, String password, String branchOrTag) throws GitAPIException {

        String dirName = url.substring(url.lastIndexOf("/") + 1);
        dirName = dirName.replace(".git", "");

        File workDir = new File("/data/gitcode/" + dirName + "/" + DateUtil.date().toString("yyyyMMddHHmmss"));

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

        // support public project by anon
        if (user != null && password != null) {
            UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(user, password);
            cloneCommand.setCredentialsProvider(provider);
        }


        Git git = cloneCommand.call();



        LogCommand logcmd = git.log();
        Iterable<RevCommit> logResult = logcmd.call();
        RevCommit next = logResult.iterator().next();

        String submitMessage = next.getFullMessage();
        log.info("代码日志：{}", submitMessage);


        git.close();
        log.info("代码获取完毕, 共 {} M", FileUtil.readableFileSize(FileUtils.sizeOfDirectory(workDir) ) );

        log.info("耗时：{} 秒", (System.currentTimeMillis() - start) / 1000);


        return new CloneResult(workDir, submitMessage.trim(), LocalDateTime.ofInstant(Instant.ofEpochMilli(next.getCommitTime() * 1000L), ZoneId.systemDefault()));
    }

}
