package com.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Git worktree 管理器 — 为 GENERAL 子 Agent 提供文件系统级隔离。
 *
 * <h3>为什么需要 worktree</h3>
 * 多个 GENERAL 子 Agent 并行执行时，可能同时写同一个文件。
 * worktree 给每个子 Agent 一份独立的项目副本，写完互不覆盖。
 * 子 Agent 完成后，父 Agent 通过 git diff 看到变更，决定是否合入主分支。
 *
 * <h3>生命周期</h3>
 * create() → 子 Agent 在 worktree 中执行 → getDiff() 取变更 → remove() 清理
 */
public class WorktreeManager {
    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    private final Path baseDir;
    private final Set<Path> worktrees = ConcurrentHashMap.newKeySet();

    public WorktreeManager() {
        this.baseDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-worktrees");
    }

    /**
     * 创建一个临时 git worktree。
     * @return worktree 的绝对路径，失败返回 null
     */
    public String create() {
        try {
            Files.createDirectories(baseDir);
            String id = UUID.randomUUID().toString().substring(0, 8);
            Path wt = baseDir.resolve("wt-" + id);

            ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "add", "--detach", wt.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done || p.exitValue() != 0) {
                String err = new String(p.getInputStream().readAllBytes());
                log.warn("Worktree creation failed: {}", err);
                return null;
            }

            worktrees.add(wt);
            log.info("Worktree created: {}", wt);
            return wt.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Worktree creation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 删除 worktree 并递归清理物理目录。
     * 先 git worktree remove（清理 git 元数据），
     * 再 Files.walk 逆序删除残留文件（如编译产物 target/）。
     */
    public void remove(String path) {
        try {
            Path wt = Path.of(path);
            // 第一步：git 清理元数据
            new ProcessBuilder("git", "worktree", "remove", wt.toString(), "--force")
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS);
            worktrees.remove(wt);

            // 第二步：递归删除物理残留（未被 git 跟踪的文件）
            if (Files.exists(wt)) {
                Files.walk(wt)
                    .sorted(Comparator.reverseOrder())  // 先删文件再删目录
                    .forEach(f -> {
                        try { Files.delete(f); } catch (Exception ignored) {}
                    });
            }
            log.info("Worktree removed: {}", wt);
        } catch (Exception e) {
            log.warn("Worktree removal failed for {}: {}", path, e.getMessage());
        }
    }

    /**
     * 获取 worktree 中的 git diff（子 Agent 所做的所有修改）。
     * @return diff 文本，失败返回空字符串
     */
    public String getDiff(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", path, "diff");//获得Agent 相对于它初始状态的所有修改。
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(10, TimeUnit.SECONDS);
            String diff = new String(p.getInputStream().readAllBytes());
            return diff.trim();
        } catch (Exception e) {
            log.warn("Failed to get diff from {}: {}", path, e.getMessage());
            return "";
        }
    }
}
