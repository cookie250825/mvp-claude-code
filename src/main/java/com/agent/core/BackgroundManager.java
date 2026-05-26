package com.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 后台异步任务管理器 — 让长时间命令不阻塞主对话。
 *
 * <h3>解决什么问题</h3>
 * 假设用户说"帮我编译这个 Java 项目"。编译可能要 60 秒。
 * 如果用 BashTool 同步执行，Agent 循环卡住 60 秒——用户看着黑屏发呆。
 * BackgroundManager 把编译扔到后台线程，立刻告诉用户"已启动"，
 * Agent 继续下一轮对话。编译完了通过通知队列把结果送回上下文。
 *
 * <h3>交互流程</h3>
 * 1. LLM 调 background_run(command="mvn compile", timeout=120)
 * 2. 主线程立即返回 "后台任务 abc123 已启动"
 * 3. 后台线程开始编译
 * 4. Agent 继续下一轮，drain() 检查有没有完成通知
 * 5. 编译完了 → 通知入队 → 下一轮 drain() 拿到 → 注入上下文
 * 6. LLM 看到结果后告诉用户 "编译成功了"
 *
 * <h3>线程安全</h3>
 * tasks 用 ConcurrentHashMap，notifications 用 ConcurrentLinkedQueue。
 * 主线程和后台线程同时读写也不会出事。
 */
public class BackgroundManager {
    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);

    /**
     * 后台任务 — 记录一个正在运行的异步命令。
     */
    public static class Task {
        /** 任务唯一 ID（UUID 前 8 位） */
        public final String id;
        /** 状态：running / completed / error */
        public volatile String status;
        /** 任务结果（命令输出或错误信息） */
        public volatile String result;
        /** 要执行的 shell 命令 */
        public final String command;

        Task(String id, String command) {
            this.id = id;
            this.command = command;
            this.status = "running";
        }
    }

    /** 任务 ID → 任务对象（线程安全） */
    public final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** 通知队列：后台线程往里面放完成通知，主循环 drain() 取走 */
    public final Queue<Map<String, Object>> notifications = new ConcurrentLinkedQueue<>();

    /**
     * 启动一个后台任务。
     *
     * <h3>做了什么</h3>
     * 1. 生成 taskId（UUID 前 8 位）
     * 2. 创建 Task 对象，标记为 running
     * 3. 起一个守护线程执行命令
     * 4. 立刻返回 "后台任务 xxx 已启动"
     *
     * @param command 要执行的 shell 命令
     * @param timeout 超时秒数
     * @return 用户可读的启动确认消息
     */
    public String run(String command, int timeout) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Task task = new Task(id, command);
        tasks.put(id, task);

        // 守护线程：主程序退出时自动终止，不会卡住 JVM
        Thread t = new Thread(() -> execute(task, timeout));
        t.setDaemon(true);
        t.start();

        log.info("Background task {} started: {}", id, command);
        return "后台任务 " + id + " 已启动: " + command.substring(0, Math.min(60, command.length()));
    }

    /**
     * 在后台线程中执行命令（不阻塞主循环）。
     *
     * <h3>执行细节</h3>
     * 1. bash -c 执行命令
     * 2. 读 stdout/stderr，最多缓冲 5MB（防超大输出撑爆内存）
     * 3. 等 timeout 秒，超时就杀进程
     * 4. 结果截断到 50K 字符（工具输出太长没意义）
     * 5. 把完成通知放入队列
     *
     * @param task    任务对象
     * @param timeout 超时秒数
     */
    private void execute(Task task, int timeout) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", task.command);
            pb.redirectErrorStream(true);  // stderr 合并到 stdout
            Process p = pb.start();

            // 读进程输出，最多 5MB（防止恶意命令）
            try (var r = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = r.read(buf)) >= 0) {
                    output.append(new String(buf, 0, n));
                    if (output.length() > 5_000_000) break;
                }
            }

            // 等进程结束（带超时）
            boolean done = p.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                task.status = "error";
                task.result = "超时 (" + timeout + "s)";
            } else {
                task.status = "completed";
                String text = output.toString().trim();
                task.result = text.length() > 50000 ? text.substring(0, 50000) : text;
            }
        } catch (Exception e) {
            task.status = "error";
            task.result = e.getMessage();
        }

        // 构造通知，放入队列
        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("task_id", task.id);
        notif.put("status", task.status);
        String preview = task.result != null ? task.result : "";
        if (preview.length() > 500) preview = preview.substring(0, 500);  // 通知里只放前 500 字预览
        notif.put("result", preview);
        notifications.add(notif);

        log.info("Background task {} finished: {}", task.id, task.status);
    }

    /**
     * 查询任务状态。
     *
     * @param taskId 要查的任务 ID；传 null 或空字符串则列出全部任务
     * @return 任务状态文本
     */
    public String check(String taskId) {
        // 查询单个
        if (taskId != null && !taskId.isEmpty()) {
            Task t = tasks.get(taskId);
            if (t == null) return "未知任务: " + taskId;
            return "[" + t.status + "] " + (t.result != null ? t.result : "(运行中)");
        }
        // 列出全部
        if (tasks.isEmpty()) return "无后台任务。";
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks.values()) {
            sb.append(t.id).append(": [").append(t.status).append("] ")
              .append(t.command.substring(0, Math.min(50, t.command.length())))
              .append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 排空通知队列 — AgentLoop 每轮调用一次。
     *
     * <h3>为什么叫 drain</h3>
     * 一次性把队列里所有通知全部取走（poll 到 null 为止），
     * 返回一个 List。AgentLoop 遍历这个 List，逐条注入上下文。
     *
     * @return 本轮收到的所有后台任务完成通知
     */
    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> n;
        while ((n = notifications.poll()) != null) out.add(n);
        return out;
    }
}
