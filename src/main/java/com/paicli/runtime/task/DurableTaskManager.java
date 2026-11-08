package com.paicli.runtime.task;

import com.paicli.agent.ExecutionOutcome;
import com.paicli.runtime.RootTaskScope;
import java.io.Closeable;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DurableTaskManager implements Closeable {
    private final Path dbPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final Connection connection;
    private final Map<String, RootTaskScope> runningTasks = new ConcurrentHashMap<>();
    private static final Duration CANCEL_CLEANUP_WAIT = Duration.ofSeconds(2);
    private ExecutorService workers;
    private volatile boolean running;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this.dbPath = dbPath;
        this.runner = runner;
        this.workerCount = Math.max(1, workerCount);
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new SQLException("无法创建任务数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
        recoverRunningTasks();
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        return new DurableTaskManager(defaultDbPath(), runner, workerCount());
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("paicli.task.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_TASK_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".paicli", "tasks").toString();
        }
        return Path.of(configured).resolve("tasks.db");
    }

    private static int workerCount() {
        String configured = System.getProperty("paicli.task.workers");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_TASK_WORKERS");
        }
        if (configured == null || configured.isBlank()) {
            return 2;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }
    //创建默认固定线程池 进入workLoop循环
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread thread = new Thread(r, "paicli-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::workerLoop);
        }
    }
    //把任务存入数据库
    public synchronized DurableTask enqueue(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }
        String id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_tasks (id, status, prompt, created_at, cleanup_state)
                VALUES (?, ?, ?, ?, 'not_started')
                """)) {
            ps.setString(1, id);
            ps.setString(2, TaskStatus.ENQUEUED.value());
            ps.setString(3, prompt.trim());
            ps.setString(4, now);
            ps.executeUpdate();//插入这条待执行的任务存放到数据库
            notifyAll();//通知后台进程
            return find(id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("提交后台任务失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<DurableTask> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<DurableTask> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setInt(1, bounded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
        return tasks;
    }

    public synchronized Optional<DurableTask> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM runtime_tasks WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
    }

    public boolean cancel(String id) {
        RootTaskScope scope;
        synchronized (this) {
            Optional<DurableTask> current = find(id);
            if (current.isEmpty() || current.get().terminal()) return false;
            scope = runningTasks.get(id);
            if (scope != null) scope.requestCancel();
            markTerminal(id, TaskStatus.CANCELED, current.get().result(), "用户取消", current.get().startedAt(),
                    ExecutionOutcome.Reason.CANCELLED);
            updateCleanupState(id, scope == null ? TaskCleanupState.NOT_STARTED : TaskCleanupState.CANCEL_REQUESTED);
            notifyAll();
        }
        if (scope != null) {
            boolean stopped = false;
            try { stopped = scope.awaitStopped(CANCEL_CLEANUP_WAIT); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (this) {
                updateCleanupState(id, stopped ? TaskCleanupState.STOPPED : TaskCleanupState.INCOMPLETE);
            }
        }
        return true;
    }

    public Path dbPath() {
        return dbPath;
    }

    private void workerLoop() {
        while (running) {
            DurableTask task = null;
            try {
                RootTaskScope scope = new RootTaskScope();//任务运行的控制对象
                task = claimNext(scope);
                if (task == null) {
                    synchronized (this) {
                        wait(300);
                    }
                    continue;
                }
                String taskId = task.id();
                Instant startedAt = Instant.now();
                String prompt = task.prompt();
                try {
                    //执行任务
                    ExecutionOutcome outcome = scope.run(() -> java.util.Objects.requireNonNull(
                            runner.run(prompt), "task outcome"));
                    //保存结果 查询一遍
                    synchronized (this) {
                        DurableTask latest = find(taskId).orElse(null);
                        if (latest != null && !latest.terminal()) {
                            markTerminal(taskId, TaskStatus.fromOutcome(outcome), outcome.content(),
                                    outcome.completed() ? null : outcome.reason().name(), startedAt, outcome.reason());
                        } else if (latest != null && latest.status() == TaskStatus.CANCELED
                                && outcome.reason() == ExecutionOutcome.Reason.CANCELLED) {
                            retainCancelledContent(taskId, outcome.content());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    synchronized (this) {
                        markTerminal(taskId, TaskStatus.CANCELED, "", "任务线程被中断", startedAt,
                                ExecutionOutcome.Reason.CANCELLED);
                    }
                } catch (Exception e) {
                    synchronized (this) {
                        DurableTask latest = find(taskId).orElse(null);
                        if (latest != null && !latest.terminal()) {
                            markTerminal(taskId, TaskStatus.FAILED, "", e.getMessage(), startedAt,
                                    ExecutionOutcome.Reason.EXECUTION_ERROR);
                        }
                    }
                } finally {
                    runningTasks.remove(taskId, scope);
                    synchronized (this) { updateCleanupState(taskId, TaskCleanupState.STOPPED); }
                    Thread.interrupted(); // This worker may now process an independent queued task.
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Worker loop must stay alive; individual failures are recorded on the task row when possible.
            }
        }
    }

    private synchronized DurableTask claimNext(RootTaskScope scope) throws SQLException {
        if (!running) return null;
        connection.setAutoCommit(false);
        try {
            DurableTask task = null;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT * FROM runtime_tasks
                    WHERE status = ?
                    ORDER BY created_at ASC
                    LIMIT 1
                    """)) {
                select.setString(1, TaskStatus.ENQUEUED.value());
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        task = fromRow(rs);
                    }
                }
            }
            if (task == null) {
                connection.commit();
                return null;
            }
            String now = Instant.now().toString();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_tasks
                    SET status = ?, started_at = ?, updated_at = ?, cleanup_state = 'running'
                    WHERE id = ? AND status = ?
                    """)) {
                update.setString(1, TaskStatus.RUNNING.value());
                update.setString(2, now);
                update.setString(3, now);
                update.setString(4, task.id());
                update.setString(5, TaskStatus.ENQUEUED.value());
                //变化0行 回滚
                if (update.executeUpdate() == 0) {
                    connection.rollback();
                    return null;
                }
            }
            connection.commit();
            runningTasks.put(task.id(), scope);
            return find(task.id()).orElse(task);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private synchronized void markTerminal(String id, TaskStatus status, String result, String error,
                                           Instant startedAt, ExecutionOutcome.Reason reason) {
        String now = Instant.now().toString();
        long durationMs = startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = ?, error = ?, finished_at = ?, duration_ms = ?, updated_at = ?, reason = ?
                WHERE id = ? AND status IN ('enqueued', 'running')
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, now);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, reason.name());
            ps.setString(8, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务失败: " + e.getMessage(), e);
        }
    }

    private synchronized void updateCleanupState(String id, TaskCleanupState state) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks SET cleanup_state = ?
                WHERE id = ? AND (cleanup_state IS NULL OR cleanup_state <> 'stopped')
                """)) {
            ps.setString(1, state.name().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新任务清理状态失败: " + e.getMessage(), e);
        }
    }

    /** Attach actual cancellation output without changing an already committed terminal state. */
    private void retainCancelledContent(String id, String content) throws SQLException {
        if (content == null || content.isBlank()) return;
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks SET result = ?
                WHERE id = ? AND status = 'canceled' AND reason = 'CANCELLED'
                  AND (result IS NULL OR result = '')
                """)) {
            ps.setString(1, content);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_tasks (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT,
                        duration_ms INTEGER DEFAULT 0
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_status ON runtime_tasks(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_created ON runtime_tasks(created_at)");
            boolean hasReason = false;
            boolean hasCleanupState = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(runtime_tasks)")) {
                while (columns.next()) {
                    if ("reason".equals(columns.getString("name"))) hasReason = true;
                    if ("cleanup_state".equals(columns.getString("name"))) hasCleanupState = true;
                }
            }
            if (!hasReason) stmt.execute("ALTER TABLE runtime_tasks ADD COLUMN reason TEXT");
            if (!hasCleanupState) stmt.execute("ALTER TABLE runtime_tasks ADD COLUMN cleanup_state TEXT");
        }
    }

    private synchronized void recoverRunningTasks() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, updated_at = ?, cleanup_state = 'incomplete'
                WHERE status = ?
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, TaskStatus.RUNNING.value());
            ps.executeUpdate();
        }
    }

    private DurableTask fromRow(ResultSet rs) throws SQLException {
        return new DurableTask(
                rs.getString("id"),
                TaskStatus.from(rs.getString("status")),
                rs.getString("prompt"),
                rs.getString("result"),
                rs.getString("error"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")),
                rs.getLong("duration_ms"),
                parseReason(rs.getString("reason")),
                parseCleanupState(rs.getString("cleanup_state"))
        );
    }

    private static ExecutionOutcome.Reason parseReason(String value) {
        if (value == null) return null; // Historical rows remain unknown, never inferred from prose.
        try { return ExecutionOutcome.Reason.valueOf(value); }
        catch (IllegalArgumentException unknownFutureReason) { return null; }
    }

    private static TaskCleanupState parseCleanupState(String value) {
        if (value == null) return null; // Old rows have no observed cleanup state.
        try { return TaskCleanupState.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException unknownFutureState) { return null; }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    @Override
    public void close() {
        ExecutorService pool;
        Map<String, RootTaskScope> active;
        synchronized (this) {
            running = false;
            notifyAll();
            pool = workers;
            active = Map.copyOf(runningTasks);
        }
        active.values().forEach(RootTaskScope::requestCancel);
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            for (Map.Entry<String, RootTaskScope> entry : active.entrySet()) {
                if (!entry.getValue().isStopped()) {
                    updateCleanupState(entry.getKey(), TaskCleanupState.INCOMPLETE);
                }
            }
            try { connection.close(); }
            catch (SQLException ignored) { }
        }
    }
}
