package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** CREATE_NEW-only writer bound to one newly-created private generation root. */
final class PrivateSourceWriter {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> EXECUTABLE_MODE =
            PosixFilePermissions.fromString("rwx------");

    private final Path root;

    PrivateSourceWriter(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    Path directory(String relative) throws IOException {
        Path target = resolve(relative);
        Path current = root;
        Path relativePath = root.relativize(target);
        for (Path segment : relativePath) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("private source directory path is unsafe: " + current);
                }
            } else {
                Files.createDirectory(current,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            }
            Files.setPosixFilePermissions(current, DIRECTORY_MODE);
        }
        return target;
    }

    Path text(String relative, String content) throws IOException {
        return bytes(relative, content.getBytes(StandardCharsets.UTF_8), false);
    }

    Path executable(String relative, String content) throws IOException {
        if (!content.startsWith("#!")) {
            throw new IllegalArgumentException("direct executable wrapper must start with a shebang");
        }
        return bytes(relative, content.getBytes(StandardCharsets.UTF_8), true);
    }

    Path json(String relative, Object value) throws IOException {
        byte[] encoded = JSON.writeValueAsBytes(value);
        JSON.readTree(encoded);
        return bytes(relative, encoded, false);
    }

    Path copy(String sourceRelative, String targetRelative) throws IOException {
        Path source = resolve(sourceRelative);
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("private source copy input is unsafe: " + source);
        }
        return bytes(targetRelative, Files.readAllBytes(source), false);
    }

    private Path bytes(String relative, byte[] content, boolean executable) throws IOException {
        Path target = resolve(relative);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("private source file has no parent: " + target);
        }
        if (!parent.equals(root)) {
            directory(root.relativize(parent).toString());
        }
        Files.createFile(target, PosixFilePermissions.asFileAttribute(
                executable ? EXECUTABLE_MODE : FILE_MODE));
        Files.write(target, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(target, executable ? EXECUTABLE_MODE : FILE_MODE);
        return target;
    }

    private Path resolve(String relative) {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("private source path must be a portable relative path");
        }
        Path parsed = Path.of(relative);
        if (parsed.isAbsolute()) {
            throw new IllegalArgumentException("private source path must be relative: " + relative);
        }
        Path normalized = parsed.normalize();
        if (normalized.toString().isBlank() || normalized.equals(Path.of("."))
                || normalized.startsWith("..")) {
            throw new IllegalArgumentException("private source path escapes root: " + relative);
        }
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("private source path escapes root: " + relative);
        }
        return target;
    }
}
