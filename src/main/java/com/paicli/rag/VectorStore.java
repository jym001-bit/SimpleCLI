package com.paicli.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 向量存储 + 代码关系图谱持久化
 * <p>
 * 向量以 JSON 数组形式存储在 SQLite 中，检索时在内存计算余弦相似度。
 * 对于代码库规模（通常几百到几千个块），此方案足够；规模再大可换 FAISS / pgvector 等。
 */
public class VectorStore implements AutoCloseable {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection connection;
    private final String projectPath;

    public VectorStore(String projectPath) throws SQLException {
        this.projectPath = projectPath;
        String dbDir = System.getProperty("paicli.rag.dir",
                System.getProperty("user.home") + "/.paicli/rag");
        java.io.File dir = new java.io.File(dbDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String dbPath = dir.getAbsolutePath() + "/codebase.db";
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
    }

    private void initTables() throws SQLException {
        // 代码块表：存储分块内容和向量
        String createChunks = """
                CREATE TABLE IF NOT EXISTS code_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    chunk_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding_json TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        // 代码关系表：存储类/方法间的依赖关系
        String createRelations = """
                CREATE TABLE IF NOT EXISTS code_relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    from_file TEXT NOT NULL,
                    from_name TEXT NOT NULL,
                    to_file TEXT,
                    to_name TEXT,
                    relation_type TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        // 已索引文件的内容指纹：内容和 Embedding 模型都没变的文件，增量索引时跳过
        String createIndexedFiles = """
                CREATE TABLE IF NOT EXISTS indexed_files (
                    project_path TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    content_sha256 TEXT NOT NULL,
                    embedding_model TEXT NOT NULL,
                    PRIMARY KEY (project_path, file_path)
                )
                """;

        // 索引加速查询
        String createIdxProject = "CREATE INDEX IF NOT EXISTS idx_project ON code_chunks(project_path)";
        String createIdxFile = "CREATE INDEX IF NOT EXISTS idx_file ON code_chunks(file_path)";
        String createIdxType = "CREATE INDEX IF NOT EXISTS idx_type ON code_chunks(chunk_type)";
        String createIdxRelProject = "CREATE INDEX IF NOT EXISTS idx_rel_project ON code_relations(project_path)";
        String createIdxRelFrom = "CREATE INDEX IF NOT EXISTS idx_rel_from ON code_relations(from_name)";
        String createIdxRelTo = "CREATE INDEX IF NOT EXISTS idx_rel_to ON code_relations(to_name)";
        String createIdxRelFile = "CREATE INDEX IF NOT EXISTS idx_rel_from_file ON code_relations(project_path, from_file)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createChunks);
            stmt.execute(createRelations);
            stmt.execute(createIdxProject);
            stmt.execute(createIdxFile);
            stmt.execute(createIdxType);
            stmt.execute(createIdxRelProject);
            stmt.execute(createIdxRelFrom);
            stmt.execute(createIdxRelTo);
            stmt.execute(createIndexedFiles);
            stmt.execute(createIdxRelFile);
        }
    }

    /**
     * 清空指定项目的索引数据
     */
    public void clearProject() throws SQLException {
        String deleteChunks = "DELETE FROM code_chunks WHERE project_path = ?";
        String deleteRelations = "DELETE FROM code_relations WHERE project_path = ?";
        String deleteFiles = "DELETE FROM indexed_files WHERE project_path = ?";
        try (PreparedStatement ps1 = connection.prepareStatement(deleteChunks);
             PreparedStatement ps2 = connection.prepareStatement(deleteRelations);
             PreparedStatement ps3 = connection.prepareStatement(deleteFiles)) {
            ps1.setString(1, projectPath);
            ps2.setString(1, projectPath);
            ps3.setString(1, projectPath);
            ps1.executeUpdate();
            ps2.executeUpdate();
            ps3.executeUpdate();
        }
    }

    /** 当前项目已索引的文件及其内容指纹。 */
    public Map<String, IndexedFile> indexedFiles() throws SQLException {
        String sql = "SELECT file_path, content_sha256, embedding_model FROM indexed_files WHERE project_path = ?";
        Map<String, IndexedFile> files = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.put(rs.getString("file_path"),
                            new IndexedFile(rs.getString("content_sha256"), rs.getString("embedding_model")));
                }
            }
        }
        return files;
    }

    /**
     * 整体替换一批文件的索引：删掉这些文件旧的代码块和关系，写入新的，并记下内容指纹。
     * 一批文件放在同一个事务里，中途失败整批回滚，旧索引保持原样。
     */
    public void replaceFiles(List<FileIndex> files) throws SQLException {
        inTransaction(() -> {
            deleteFileRows(files.stream().map(FileIndex::filePath).toList());
            List<CodeChunkEntry> entries = new ArrayList<>();
            List<CodeRelation> relations = new ArrayList<>();
            files.forEach(file -> {
                entries.addAll(file.entries());
                relations.addAll(file.relations());
            });
            insertChunkRows(entries);
            insertRelationRows(relations);
            String sql = """
                    INSERT OR REPLACE INTO indexed_files (project_path, file_path, content_sha256, embedding_model)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (FileIndex file : files) {
                    ps.setString(1, projectPath);
                    ps.setString(2, file.filePath());
                    ps.setString(3, file.contentSha256());
                    ps.setString(4, file.embeddingModel());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** 删除文件对应的代码块、关系和指纹（文件已被删除或不再索引）。 */
    public void removeFiles(Collection<String> filePaths) throws SQLException {
        if (filePaths.isEmpty()) {
            return;
        }
        inTransaction(() -> deleteFileRows(filePaths));
    }

    private void deleteFileRows(Collection<String> filePaths) throws SQLException {
        String[] sqls = {
                "DELETE FROM code_chunks WHERE project_path = ? AND file_path = ?",
                "DELETE FROM code_relations WHERE project_path = ? AND from_file = ?",
                "DELETE FROM indexed_files WHERE project_path = ? AND file_path = ?"
        };
        for (String sql : sqls) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (String filePath : filePaths) {
                    ps.setString(1, projectPath);
                    ps.setString(2, filePath);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 批量插入代码块（事务保护）
     */
    public void insertChunks(List<CodeChunkEntry> entries) throws SQLException {
        inTransaction(() -> insertChunkRows(entries));
    }

    private void insertChunkRows(List<CodeChunkEntry> entries) throws SQLException {
        String sql = """
                INSERT INTO code_chunks (project_path, file_path, chunk_type, name, content, embedding_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeChunkEntry entry : entries) {
                ps.setString(1, projectPath);
                ps.setString(2, entry.chunk.filePath());
                ps.setString(3, entry.chunk.chunkType());
                ps.setString(4, entry.chunk.name());
                ps.setString(5, entry.chunk.content());
                ps.setString(6, embeddingToJson(entry.embedding));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 批量插入代码关系（事务保护）
     */
    public void insertRelations(List<CodeRelation> relations) throws SQLException {
        inTransaction(() -> insertRelationRows(relations));
    }

    private void insertRelationRows(List<CodeRelation> relations) throws SQLException {
        String sql = """
                INSERT INTO code_relations (project_path, from_file, from_name, to_file, to_name, relation_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeRelation rel : relations) {
                ps.setString(1, projectPath);
                ps.setString(2, rel.fromFile());
                ps.setString(3, rel.fromName());
                ps.setString(4, rel.toFile());
                ps.setString(5, rel.toName());
                ps.setString(6, rel.relationType());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 语义检索：根据查询向量返回最相似的 TopK 代码块
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        String sql = "SELECT file_path, chunk_type, name, content, embedding_json FROM code_chunks WHERE project_path = ?";
        List<SearchResult> candidates = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String embeddingJson = rs.getString("embedding_json");
                    if (embeddingJson == null || embeddingJson.isEmpty()) {
                        continue;
                    }
                    float[] embedding = jsonToEmbedding(embeddingJson);
                    double similarity = cosineSimilarity(queryEmbedding, embedding);
                    candidates.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            similarity
                    ));
                }
            }
        }

        // 按相似度降序排序，取 TopK
        candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return candidates.size() > topK ? new ArrayList<>(candidates.subList(0, topK)) : candidates;
    }

    /**
     * 根据关键词检索代码块（不经过 Embedding，用于精确匹配类名/方法名）
     */
    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        String sql = """
                SELECT file_path, chunk_type, name, content FROM code_chunks
                WHERE project_path = ? AND (name LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')
                """;
        List<SearchResult> results = new ArrayList<>();
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.3
                    ));
                }
            }
        }
        return results;
    }

    /**
     * 图谱检索：查询与指定名称相关的所有关系
     */
    public List<CodeRelation> getRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type FROM code_relations
                WHERE project_path = ? AND (from_name = ? OR to_name = ?)
                """;
        List<CodeRelation> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CodeRelation(
                            rs.getString("from_file"),
                            rs.getString("from_name"),
                            rs.getString("to_file"),
                            rs.getString("to_name"),
                            rs.getString("relation_type")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * 获取指定类/方法的所有 outgoing 关系
     */
    public List<CodeRelation> getOutgoingRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type FROM code_relations
                WHERE project_path = ? AND from_name = ?
                """;
        List<CodeRelation> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CodeRelation(
                            rs.getString("from_file"),
                            rs.getString("from_name"),
                            rs.getString("to_file"),
                            rs.getString("to_name"),
                            rs.getString("relation_type")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * 统计当前项目的索引数据量
     */
    public IndexStats getStats() throws SQLException {
        String chunkSql = "SELECT COUNT(*) FROM code_chunks WHERE project_path = ?";
        String relSql = "SELECT COUNT(*) FROM code_relations WHERE project_path = ?";
        int chunks = 0;
        int relations = 0;

        try (PreparedStatement ps = connection.prepareStatement(chunkSql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) chunks = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(relSql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) relations = rs.getInt(1);
            }
        }
        return new IndexStats(chunks, relations);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String embeddingToJson(float[] embedding) {
        try {
            return mapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量序列化失败", e);
        }
    }

    private float[] jsonToEmbedding(String json) {
        try {
            return mapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量反序列化失败", e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * 带向量的代码块条目
     */
    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}

    /**
     * 检索结果
     */
    public record SearchResult(String filePath, String chunkType,
                                String name, String content, double similarity) {}

    /** 已索引文件的内容指纹和当时用的 Embedding 模型。 */
    public record IndexedFile(String contentSha256, String embeddingModel) {}

    /** 一个文件的完整索引内容。 */
    public record FileIndex(String filePath, String contentSha256, String embeddingModel,
                            List<CodeChunkEntry> entries, List<CodeRelation> relations) {}

    /**
     * 索引统计
     */
    public record IndexStats(int chunkCount, int relationCount) {}
}
