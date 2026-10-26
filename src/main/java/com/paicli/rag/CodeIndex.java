package com.paicli.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 代码索引管理器：负责将代码库分块、向量化并持久化到 VectorStore
 */
public class CodeIndex {
    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);
    // 实测 5874 个 Java 文件：单线程 4.6 秒，4 线程 1.9 秒，8 线程反而 2.3 秒（分配和 GC 压力上来了）
    private static final int DEFAULT_INDEX_THREADS = 4;
    // 超过 1MB 的通常是生成代码、压缩后的 JS 或数据文件，索引它们只会占内存和 Embedding 额度
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private final EmbeddingClient embeddingClient;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final ProgressListener progressListener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);

        static ProgressListener noop() {
            return message -> {
            };
        }
    }

    public CodeIndex() {
        this(new EmbeddingClient(), ProgressListener.noop());
    }

    public CodeIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener progressListener) {
        this.embeddingClient = embeddingClient;
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
        this.progressListener = progressListener == null ? ProgressListener.noop() : progressListener;
    }

    /**
     * 索引指定路径的代码库。
     *
     * <p>增量进行：按文件内容的 SHA-256 判断，内容和 Embedding 模型都没变的文件跳过，
     * 已删除（或超过 1MB）的文件清掉旧索引。读文件、算指纹、解析放在多个线程里并行，
     * 每个线程用自己的 JavaParser；Embedding 跨文件凑成一批再请求，每批写一次库。
     * 某一批 Embedding 失败时，这些文件保留原来的索引，下次 /index 再试。
     *
     * @param projectPath 项目根目录
     * @return 索引统计信息
     */
    public IndexResult index(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }

        emit("🔍 开始索引: " + root);

        List<Path> filesToIndex = new ArrayList<>();
        int oversized = collectFiles(root, filesToIndex);
        emit("📁 发现 " + filesToIndex.size() + " 个文件待索引"
                + (oversized > 0 ? "（另有 " + oversized + " 个超过 1MB 的文件跳过）" : ""));

        try (VectorStore store = new VectorStore(root.toString())) {
            Map<String, VectorStore.IndexedFile> known = store.indexedFiles();
            if (known.isEmpty()) {
                // 第一次索引，或者旧版本建的索引（没有文件指纹）：整体重建
                store.clearProject();
            }
            Set<String> current = new HashSet<>();
            filesToIndex.forEach(file -> current.add(file.toString()));
            List<String> removed = known.keySet().stream().filter(path -> !current.contains(path)).toList();
            store.removeFiles(removed);
            if (!removed.isEmpty()) {
                emit("🗑️ 清理 " + removed.size() + " 个已删除文件的索引");
            }

            IndexRun run = new IndexRun(store, known, filesToIndex.size());
            run.process(filesToIndex);

            VectorStore.IndexStats stats = store.getStats();
            String msg = String.format("索引完成：%d 个代码块，%d 条关系（更新 %d 个文件，%d 个没变跳过%s）",
                    stats.chunkCount(), stats.relationCount(), run.updated, run.unchanged,
                    run.failed > 0 ? "，" + run.failed + " 个失败、保留旧索引" : "");
            emit("✅ " + msg);
            return new IndexResult(stats.chunkCount(), stats.relationCount(), msg);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String error = "持久化失败: " + e.getMessage();
            emit("❌ " + error);
            log.warn("code index persistence failed for root {}", root, e);
            return new IndexResult(0, 0, error);
        }
    }

    /** 读文件 + 算指纹 + 解析的结果；内容没变时只有 unchanged=true。 */
    private record ParsedFile(Path file, String sha256, boolean unchanged,
                              List<CodeChunk> chunks, List<CodeRelation> relations, String error) {
    }

    /** 一次 /index 的处理过程：按顺序消费并行解析的结果，按批做 Embedding 和落库。 */
    private final class IndexRun {
        private final VectorStore store;
        private final Map<String, VectorStore.IndexedFile> known;
        private final int total;
        private final String embeddingModel = embeddingClient.getProvider() + ":" + embeddingClient.getModel();
        private final List<ParsedFile> pending = new ArrayList<>();
        private int pendingChunks;
        private int processed;
        private int updated;
        private int unchanged;
        private int failed;

        private IndexRun(VectorStore store, Map<String, VectorStore.IndexedFile> known, int total) {
            this.store = store;
            this.known = known;
            this.total = total;
        }

        private void process(List<Path> files) throws Exception {
            int threads = indexThreads();
            ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "paicli-code-index");
                thread.setDaemon(true);
                return thread;
            });
            // 只允许解析领先 Embedding 一小段，免得解析很快时结果全堆在内存里
            Deque<Future<ParsedFile>> window = new ArrayDeque<>();
            try {
                for (Path file : files) {
                    window.add(pool.submit(() -> parse(file)));
                    if (window.size() >= threads * 4) {
                        accept(window.poll().get());
                    }
                }
                while (!window.isEmpty()) {
                    accept(window.poll().get());
                }
                flush();
            } finally {
                pool.shutdownNow();
            }
        }

        private ParsedFile parse(Path file) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                VectorStore.IndexedFile previous = known.get(file.toString());
                if (previous != null && previous.contentSha256().equals(sha256)
                        && previous.embeddingModel().equals(embeddingModel)) {
                    return new ParsedFile(file, sha256, true, List.of(), List.of(), null);
                }
                String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                boolean java = file.toString().endsWith(".java");
                // Java 文件只解析一次，分块和关系分析共用同一棵语法树
                Optional<CompilationUnit> unit = java ? JavaSourceParser.parse(content) : Optional.empty();
                List<CodeChunk> chunks = chunker.chunk(file, content, unit);
                List<CodeRelation> relations = java ? analyzer.analyze(file, unit) : List.of();
                return new ParsedFile(file, sha256, false, chunks, relations, null);
            } catch (Exception e) {
                return new ParsedFile(file, null, false, List.of(), List.of(), e.getMessage());
            }
        }

        private void accept(ParsedFile parsed) throws SQLException {
            processed++;
            if (processed % 10 == 0 || processed == total) {
                emit(String.format("   进度: %d/%d (%s)", processed, total, parsed.file().getFileName()));
            }
            if (parsed.unchanged()) {
                unchanged++;
                return;
            }
            if (parsed.error() != null) {
                failed++;
                emit("   ⚠️ 索引失败: " + parsed.file() + " - " + parsed.error());
                log.warn("code index failed for file {}: {}", parsed.file(), parsed.error());
                return;
            }
            pending.add(parsed);
            pendingChunks += parsed.chunks().size();
            if (pendingChunks >= embeddingClient.getBatchSize()) {
                flush();
            }
        }

        private void flush() throws SQLException {
            if (pending.isEmpty()) {
                return;
            }
            List<ParsedFile> batch = List.copyOf(pending);
            pending.clear();
            pendingChunks = 0;
            try {
                store.replaceFiles(embed(batch));
                updated += batch.size();
            } catch (IOException batchError) {
                // 整批失败时逐个文件重试，找出是哪个文件的内容让接口报错，别的文件照常更新
                for (ParsedFile file : batch) {
                    try {
                        store.replaceFiles(embed(List.of(file)));
                        updated++;
                    } catch (IOException e) {
                        failed++;
                        emit("   ⚠️ Embedding 失败，保留旧索引: " + file.file() + " - " + e.getMessage());
                        log.warn("embedding failed for file {}", file.file(), e);
                    }
                }
            }
        }

        private List<VectorStore.FileIndex> embed(List<ParsedFile> files) throws IOException {
            List<String> texts = new ArrayList<>();
            files.forEach(file -> file.chunks().forEach(chunk -> texts.add(chunk.toEmbeddingText())));
            List<float[]> embeddings = embeddingClient.embedAll(texts);
            List<VectorStore.FileIndex> result = new ArrayList<>(files.size());
            int next = 0;
            for (ParsedFile file : files) {
                List<VectorStore.CodeChunkEntry> entries = new ArrayList<>(file.chunks().size());
                for (CodeChunk chunk : file.chunks()) {
                    entries.add(new VectorStore.CodeChunkEntry(chunk, embeddings.get(next++)));
                }
                result.add(new VectorStore.FileIndex(file.file().toString(), file.sha256(), embeddingModel,
                        entries, file.relations()));
            }
            return result;
        }
    }

    static int indexThreads() {
        String configured = System.getProperty("paicli.index.threads");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_INDEX_THREADS");
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INDEX_THREADS;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_INDEX_THREADS;
        }
    }

    private void emit(String message) {
        progressListener.onProgress(message);
    }

    /**
     * 收集需要索引的文件（排除常见非代码目录）
     */
    /** 收集要索引的文件，返回因为超过 1MB 而跳过的文件数。 */
    private int collectFiles(Path root, List<Path> files) {
        int[] oversized = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // 跳过常见非代码目录
                    if (dirName.equals("node_modules") || dirName.equals("target")
                            || dirName.equals("build") || dirName.equals(".git")
                            || dirName.equals(".idea") || dirName.equals(".vscode")
                            || dirName.equals("dist") || dirName.equals("out")
                            || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    // 只索引文本代码文件
                    if (name.endsWith(".java") || name.endsWith(".py")
                            || name.endsWith(".js") || name.endsWith(".ts")
                            || name.endsWith(".go") || name.endsWith(".rs")
                            || name.endsWith(".c") || name.endsWith(".cpp")
                            || name.endsWith(".h") || name.endsWith(".md")
                            || name.endsWith(".xml") || name.endsWith(".properties")
                            || name.endsWith(".yaml") || name.endsWith(".yml")
                            || name.endsWith(".json") || name.endsWith(".sh")
                            || name.endsWith(".gradle") || name.endsWith(".kt")) {
                        if (attrs.size() > MAX_FILE_BYTES) {
                            oversized[0]++;
                        } else {
                            files.add(file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            String message = "遍历文件失败: " + e.getMessage();
            emit("❌ " + message);
            log.warn("code index file traversal failed for root {}", root, e);
        }
        return oversized[0];
    }

    public record IndexResult(int chunkCount, int relationCount, String message) {}
}
