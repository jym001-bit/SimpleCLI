package com.paicli.rag;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 代码分块器：将代码文件切分为适合 Embedding 的粒度
 * <p>
 * 策略：
 * - 非 Java 文件：整个文件作为一个 chunk
 * - Java 文件：类级别 + 方法级别分块（大方法单独成块）
 */
public class CodeChunker {
    // 单个 chunk 最大字符数（中文约 1 字符 = 2~3 token，2000 字符 ≈ 4000~6000 token，安全适配 8192 上下文）
    private static final int MAX_CHUNK_CHARS = 2000;

    /**
     * 对单个文件进行分块
     */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString();

        // 非 Java 文件：按大小分段
        if (!relativePath.endsWith(".java")) {
            return chunkLargeText(relativePath, content);
        }

        // Java 文件：AST 解析分块
        return chunkJavaFile(filePath, content, JavaSourceParser.parse(content));
    }

    /**
     * 对已读入内存的文件分块；Java 文件传入已经解析好的语法树（解析失败传 empty），
     * 索引时和 {@link CodeAnalyzer} 共用同一次解析。
     */
    List<CodeChunk> chunk(Path filePath, String content, Optional<CompilationUnit> javaUnit) {
        if (!filePath.toString().endsWith(".java")) {
            return chunkLargeText(filePath.toString(), content);
        }
        return chunkJavaFile(filePath, content, javaUnit);
    }

    /**
     * 将大文本按行分段，每段不超过 MAX_CHUNK_CHARS
     */
    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        StringBuilder segment = new StringBuilder();
        int segIndex = 1;
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            if (segment.length() + lines[i].length() + 1 > MAX_CHUNK_CHARS && !segment.isEmpty()) {
                chunks.add(new CodeChunk(filePath, "file",
                        filePath + "#" + segIndex, segment.toString().trim(), startLine, i));
                segment.setLength(0);
                segIndex++;
                startLine = i + 1;
            }
            segment.append(lines[i]).append("\n");
        }

        if (!segment.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file",
                    filePath + "#" + segIndex, segment.toString().trim(), startLine, lines.length));
        }

        return chunks;
    }

    private List<CodeChunk> chunkJavaFile(Path filePath, String content, Optional<CompilationUnit> javaUnit) {
        List<CodeChunk> chunks = new ArrayList<>();
        if (javaUnit.isEmpty()) {
            // 解析失败则回退到按大小分段
            return chunkLargeText(filePath.toString(), content);
        }

        CompilationUnit cu = javaUnit.get();

        // 遍历所有类/接口声明
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int classStart = clazz.getBegin().map(p -> p.line).orElse(0);
            int classEnd = clazz.getEnd().map(p -> p.line).orElse(0);
            String className = clazz.getNameAsString();

            // 提取类声明文本（包含字段和签名）
            String classHeader = extractLines(content, classStart, Math.min(classStart + 5, classEnd));

            // 类级别 chunk
            chunks.add(CodeChunk.classChunk(
                    filePath.toString(), className,
                    classHeader, classStart, classEnd));

            // 方法级别 chunk
            clazz.getMethods().forEach(method -> {
                int methodStart = method.getBegin().map(p -> p.line).orElse(0);
                int methodEnd = method.getEnd().map(p -> p.line).orElse(0);
                String methodSignature = method.getDeclarationAsString(false, false, false);
                String methodContent = extractLines(content, methodStart, methodEnd);

                chunks.add(CodeChunk.methodChunk(
                        filePath.toString(),
                        className + "." + methodSignature,
                        methodContent, methodStart, methodEnd));
            });
        });

        // 如果 AST 没有解析出类（如空文件或特殊文件），回退到按大小分段
        if (chunks.isEmpty()) {
            return chunkLargeText(filePath.toString(), content);
        }

        return chunks;
    }

    private String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.length); i++) {
            if (i >= 0) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
