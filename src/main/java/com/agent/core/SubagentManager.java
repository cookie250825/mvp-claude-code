package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.memory.MemoryManager;
import com.agent.tools.FileTool;
import com.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 子 Agent 异步管理器 — 多子 Agent 并行执行 + worktree 隔离。
 *
 * <h3>两种执行模式</h3>
 * EXPLORE / PLAN / VERIFICATION（只读）：
 *   无限制并行。纯读操作，不存在文件冲突。
 *   直接在线程池中执行，不创建 worktree。
 *
 * GENERAL（可写）：
 *   并行执行，每个跑在独立 git worktree 里。
 *   worktree 提供文件系统级隔离——互不覆盖。
 *   完成后通过 git diff 把变更返回给父 Agent。
 *
 * <h3>线程安全</h3>
 * runningTasks → ConcurrentHashMap
 * notifications → ConcurrentLinkedQueue
 * SubagentRunner.run() 全局部变量，天然线程安全
 * AIService 无状态 HTTP 客户端，线程安全
 * FileTool 通过 ThreadLocal 隔离 workspace
 */
public class SubagentManager {
    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> notifications = new ConcurrentLinkedQueue<>();
    private final WorktreeManager worktreeManager;
    /** 已提交但未完成的总数（含排队中 + 执行中） */
    private final java.util.concurrent.atomic.AtomicInteger pendingCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public SubagentManager(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    /**
     * 异步提交一个子 Agent 任务。
     *
     * @param ai         AI 服务（共享，无状态）
     * @param dispatcher 父 Agent 工具分发器（会被 clone）
     * @param registry   工具注册表（会被 clone）
     * @param config     应用配置（GENERAL 类型会被覆写 workspace）
     * @param prompt     子任务描述
     * @param maxRounds  最大轮次
     * @param type       子 Agent 类型
     * @return 任务 ID（8 位短 UUID）
     */
    public String submit(AIService ai, ToolDispatcher dispatcher,
                         ToolRegistry registry, AppConfig config,
                         String prompt, int maxRounds, SubagentRunner.SubagentType type,
                         MemoryManager memoryManager) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        pendingCount.incrementAndGet();

        Future<?> future = pool.submit(() -> {
            String worktreePath = null;
            AppConfig subConfig = config;
            try {
                // GENERAL 类型：创建 worktree 做文件系统隔离
                if (type == SubagentRunner.SubagentType.GENERAL && worktreeManager != null) {
                    worktreePath = worktreeManager.create();
                    if (worktreePath != null) {
                        FileTool.setWorkspaceOverride(worktreePath);
                        subConfig = config.withWorkspace(worktreePath);
                        log.info("Subagent[{}] running in worktree: {}", id, worktreePath);
                    }
                }

                String result = SubagentRunner.run(ai, dispatcher, registry, subConfig,
                    prompt, maxRounds, type, memoryManager);

                // 如果有 worktree，把 git diff 附加到结果里
                if (worktreePath != null) {
                    String diff = worktreeManager.getDiff(worktreePath);
                    if (!diff.isEmpty()) {
                        result = result + "\n\n[Worktree 修改摘要 — git diff]\n```diff\n" + diff + "\n```";
                    }
                }

                Map<String, Object> notif = new LinkedHashMap<>();
                notif.put("task_id", id);
                notif.put("status", "completed");
                notif.put("type", type.name());
                notif.put("result", truncate(result, 3000));
                notifications.add(notif);
                log.info("Subagent[{}] ({}) completed", id, type);
            } catch (Exception e) {
                log.error("Subagent[{}] failed: {}", id, e.getMessage());
                Map<String, Object> notif = new LinkedHashMap<>();
                notif.put("task_id", id);
                notif.put("status", "error");
                notif.put("type", type.name());
                notif.put("result", "子 Agent 执行失败: " + e.getMessage());
                notifications.add(notif);
            } finally {
                if (worktreePath != null) {
                    FileTool.clearWorkspaceOverride();
                    if (worktreeManager != null) worktreeManager.remove(worktreePath);
                }
                runningTasks.remove(id);
                pendingCount.decrementAndGet();
            }
        });

        runningTasks.put(id, future);
        log.info("Subagent[{}] ({}) submitted, {} running tasks", id, type, runningTasks.size());
        return id;
    }

    /**
     * 排空已完成子 Agent 的通知队列。
     * AgentLoop 每轮调用一次，把完成结果注入父 Agent 对话历史。
     */
    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> n;
        while ((n = notifications.poll()) != null) out.add(n);
        return out;
    }

    /** 是否有待完成的子 Agent（含排队中 + 执行中） */
    public boolean hasRunning() {
        return pendingCount.get() > 0;
    }

    /** 待完成的子 Agent 总数（含排队中 + 执行中） */
    public int runningCount() {
        return pendingCount.get();
    }

    /** 关闭线程池 */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n\n[... 已截断，完整结果 " + text.length() + " 字符 ...]";
    }
}
