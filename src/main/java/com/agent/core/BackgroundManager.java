package com.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * S08: 后台异步任务管理。
 * 父 Agent 不必阻塞等待长时间命令，继续下一轮对话，结果通过通知队列异步返回。
 */
public class BackgroundManager {
    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);

    public static class Task {
        public final String id;
        public volatile String status;  // running | completed | error
        public volatile String result;
        public final String command;

        Task(String id, String command) {
            this.id = id;
            this.command = command;
            this.status = "running";
        }
    }

    public final Map<String, Task> tasks = new ConcurrentHashMap<>();
    public final Queue<Map<String, Object>> notifications = new ConcurrentLinkedQueue<>();

    /** 启动后台任务，立即返回 taskId */
    public String run(String command, int timeout) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Task task = new Task(id, command);
        tasks.put(id, task);

        Thread t = new Thread(() -> execute(task, timeout));
        t.setDaemon(true);
        t.start();

        log.info("Background task {} started: {}", id, command);
        return "后台任务 " + id + " 已启动: " + command.substring(0, Math.min(60, command.length()));
    }

    private void execute(Task task, int timeout) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", task.command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var r = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = r.read(buf)) >= 0) {
                    output.append(new String(buf, 0, n));
                    if (output.length() > 5_000_000) break;
                }
            }
            boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
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

        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("task_id", task.id);
        notif.put("status", task.status);
        String preview = task.result != null ? task.result : "";
        if (preview.length() > 500) preview = preview.substring(0, 500);
        notif.put("result", preview);
        notifications.add(notif);
        log.info("Background task {} finished: {}", task.id, task.status);
    }

    /** 查询特定或全部任务状态 */
    public String check(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            Task t = tasks.get(taskId);
            if (t == null) return "未知任务: " + taskId;
            return "[" + t.status + "] " + (t.result != null ? t.result : "(运行中)");
        }
        if (tasks.isEmpty()) return "无后台任务。";
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks.values()) {
            sb.append(t.id).append(": [").append(t.status).append("] ")
              .append(t.command.substring(0, Math.min(50, t.command.length())))
              .append("\n");
        }
        return sb.toString().trim();
    }

    /** 排空通知队列，注入到 AgentLoop */
    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> n;
        while ((n = notifications.poll()) != null) out.add(n);
        return out;
    }
}
