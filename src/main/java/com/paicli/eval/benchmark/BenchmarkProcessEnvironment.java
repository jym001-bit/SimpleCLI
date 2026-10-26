package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.File;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the deliberately small, credential-free environment inherited by benchmark children. */
final class BenchmarkProcessEnvironment {
    private static volatile Map<String, String> cachedToolchainFingerprints;
    private static final List<Path> FIXED_TOOLCHAIN_CANDIDATES = List.of(
            Path.of(System.getProperty("java.home"), "bin"),
            Path.of("/usr/bin"),
            Path.of("/bin"),
            Path.of("/usr/sbin"),
            Path.of("/sbin"),
            Path.of("/opt/homebrew/bin"),
            Path.of("/opt/homebrew/sbin"),
            Path.of("/usr/local/bin"),
            Path.of("/usr/local/sbin"));

    private BenchmarkProcessEnvironment() {
    }

    static void sanitize(Map<String, String> environment, Path isolatedHome, Path isolatedTemp) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        if (isolatedHome == null) {
            throw new IllegalArgumentException("isolatedHome must not be null");
        }
        if (isolatedTemp == null) {
            throw new IllegalArgumentException("isolatedTemp must not be null");
        }
        environment.clear();
        environment.put("PATH", fixedPath());

        String home = isolatedHome.toAbsolutePath().normalize().toString();
        String temp = isolatedTemp.toAbsolutePath().normalize().toString();
        environment.put("HOME", home);
        environment.put("USERPROFILE", home);
        environment.put("XDG_CONFIG_HOME", Path.of(home, ".config").toString());
        environment.put("XDG_CACHE_HOME", Path.of(home, ".cache").toString());
        environment.put("XDG_DATA_HOME", Path.of(home, ".local", "share").toString());
        environment.put("TMPDIR", temp);
        environment.put("TMP", temp);
        environment.put("TEMP", temp);
        environment.put("TZ", "UTC");
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
    }

    static String fixedPath() {
        return String.join(File.pathSeparator,
                fixedToolchainDirectories().stream().map(Path::toString).toList());
    }

    static List<Path> fixedToolchainDirectories() {
        List<Path> directories = new ArrayList<>();
        for (Path candidate : FIXED_TOOLCHAIN_CANDIDATES) {
            try {
                Path real = candidate.toAbsolutePath().normalize().toRealPath();
                if (Files.isDirectory(real) && !isBelowUserHome(real)) {
                    directories.add(real);
                }
            } catch (IOException | RuntimeException ignored) {
                // Missing fixed toolchains are omitted and surfaced by the coordinator fingerprint map.
            }
        }
        return directories.stream().distinct().toList();
    }

    static synchronized Map<String, String> toolchainFingerprints() throws IOException {
        if (cachedToolchainFingerprints != null) {
            return cachedToolchainFingerprints;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String executable : List.of("bash", "java", "javac", "python3", "node")) {
            Path resolved = resolveExecutable(executable);
            result.put(executable, resolved == null
                    ? "UNAVAILABLE"
                    : resolved + "#sha256=" + sha256(resolved));
        }
        cachedToolchainFingerprints = Map.copyOf(result);
        return cachedToolchainFingerprints;
    }

    static Path resolveExecutable(String executable) {
        for (Path directory : fixedToolchainDirectories()) {
            Path candidate = directory.resolve(executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                try {
                    Path real = candidate.toRealPath();
                    if (!isBelowUserHome(real)) {
                        return real;
                    }
                } catch (IOException ignored) {
                    // Keep looking through the fixed list.
                }
            }
        }
        return null;
    }

    private static boolean isBelowUserHome(Path path) {
        String rawHome = System.getProperty("user.home");
        if (rawHome == null || rawHome.isBlank()) {
            return false;
        }
        Path home = Path.of(rawHome).toAbsolutePath().normalize();
        return path.startsWith(home);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static Path prepareIsolatedTemp(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path temp = root.resolve(".paicli-benchmark-tmp").normalize();
        if (!temp.startsWith(root)) {
            throw new IOException("benchmark temp directory escapes workspace");
        }
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(temp)
                    || !Files.isDirectory(temp, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("benchmark temp path is not a safe directory: " + temp);
            }
        } else {
            try {
                Files.createDirectory(temp, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectory(temp);
            }
        }
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Platform defaults apply when POSIX permissions are unavailable.
        }
        return temp;
    }

    static Path preparePrivateDirectory(Path directory) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)
                    || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("benchmark private path is not a safe directory: " + target);
            }
        } else {
            try {
                Files.createDirectory(target, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectory(target);
            }
        }
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Platform defaults apply when POSIX permissions are unavailable.
        }
        return target;
    }

    static boolean isCredentialLike(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("AUTHORIZATION")
                || normalized.equals("AWS_SESSION_TOKEN")
                || normalized.equals("GOOGLE_APPLICATION_CREDENTIALS")
                || normalized.endsWith("_API_KEY")
                || normalized.endsWith("_TOKEN")
                || normalized.endsWith("_ACCESS_KEY")
                || normalized.endsWith("_SECRET")
                || normalized.endsWith("_PASSWORD")
                || normalized.endsWith("_CREDENTIAL")
                || normalized.endsWith("_CREDENTIALS")
                || normalized.contains("PRIVATE_KEY")
                || normalized.contains("SECRET_ACCESS_KEY")
                || normalized.contains("CLIENT_SECRET");
    }

}
