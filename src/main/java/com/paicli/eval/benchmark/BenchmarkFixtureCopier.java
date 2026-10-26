package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Copies a fixture into a fresh workspace without following or accepting symbolic links. */
final class BenchmarkFixtureCopier {
    private BenchmarkFixtureCopier() {
    }

    static void verifySafe(Path fixture) throws IOException {
        requireSafeRoot(fixture, "fixture");
        if (Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(fixture, new RejectingVisitor(null, fixture));
    }

    static void copy(Path fixture, Path workspace) throws IOException {
        Path source = requireSafeRoot(fixture, "fixture");
        Path target = requireSafeRoot(workspace, "workspace");
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace is not a directory: " + target);
        }
        try (var children = Files.list(target)) {
            if (children.findAny().isPresent()) {
                throw new IOException("workspace must be empty before fixture copy: " + target);
            }
        }

        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new RejectingVisitor(target, source));
    }

    private static Path requireSafeRoot(Path raw, String label) throws IOException {
        if (raw == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        Path path = raw.toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " does not exist: " + path);
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("symbolic links are not allowed in benchmark " + label + ": " + path);
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular file or directory: " + path);
        }
        return path;
    }

    private static final class RejectingVisitor extends SimpleFileVisitor<Path> {
        private final Path destination;
        private final Path sourceRoot;

        private RejectingVisitor(Path destination, Path sourceRoot) {
            this.destination = destination;
            this.sourceRoot = sourceRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            rejectLink(directory, attributes);
            if (destination != null) {
                Path relative = sourceRoot.relativize(directory);
                Path targetDirectory = destination.resolve(relative).normalize();
                requireInsideDestination(targetDirectory);
                if (!relative.toString().isEmpty()) {
                    Files.createDirectory(targetDirectory);
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            rejectLink(file, attributes);
            if (!attributes.isRegularFile()) {
                throw new IOException("fixture contains a non-regular file: " + file);
            }
            if (destination != null) {
                Path relative = sourceRoot.relativize(file);
                Path targetFile = destination.resolve(relative).normalize();
                requireInsideDestination(targetFile);
                Files.copy(file, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
            }
            return FileVisitResult.CONTINUE;
        }

        private void rejectLink(Path path, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
                throw new IOException("fixture contains a symbolic link: " + path);
            }
        }

        private void requireInsideDestination(Path target) throws IOException {
            if (!target.startsWith(destination)) {
                throw new IOException("fixture copy target escapes workspace: " + target);
            }
        }
    }
}
