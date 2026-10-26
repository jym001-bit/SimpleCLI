package com.paicli.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.Optional;

/**
 * Java 源码解析入口。JavaParser 实例不是线程安全的（官方说明），并行索引时每个线程用自己的一份。
 * 设置 Java 17 语言级别，支持 text block（"""）、record、sealed class 等语法。
 */
final class JavaSourceParser {

    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)));

    private JavaSourceParser() {
    }

    /** 解析失败返回 empty，调用方自己决定怎么退化。 */
    static Optional<CompilationUnit> parse(String content) {
        ParseResult<CompilationUnit> result = PARSER.get().parse(content);
        if (!result.isSuccessful()) {
            return Optional.empty();
        }
        return result.getResult();
    }
}
