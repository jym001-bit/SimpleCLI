package com.paicli.eval.benchmark.finalset.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Owner-operated CLI for writing a partial private source to an explicit Git-external path. */
public final class FinalSourceGeneratorMain {
    private FinalSourceGeneratorMain() {
    }

    public static void main(String[] args) throws Exception {
        Parsed parsed = parse(args);
        String seed = readPrivateSeed(parsed.seedFile());
        FinalSourceGenerator generator = new FinalSourceGenerator();
        FinalSourceGenerator.GenerationRequest request =
                new FinalSourceGenerator.GenerationRequest(parsed.destination(), seed);
        FinalSourceGenerator.GenerationResult result;
        if ("generate-incomplete".equals(parsed.action())) {
            result = generator.generateIncompleteSource(request);
        } else if ("generate-final".equals(parsed.action())) {
            result = generator.generateFinalSource(request);
        } else {
            throw new IllegalArgumentException("unsupported generator action: " + parsed.action());
        }
        System.out.println("sourceRoot=" + result.sourceRoot());
        System.out.println("finalReady=" + result.manifest().finalReady());
        System.out.println("implementedRecipes=" + result.manifest().implementedRecipeCount());
        System.out.println("recipeCount=" + result.manifest().recipeCount());
        System.out.println("treeSha256=" + result.completeTreeSha256());
    }

    private static Parsed parse(String[] args) {
        if (args == null || args.length != 5) {
            throw new IllegalArgumentException(
                    "usage: generate-incomplete|generate-final "
                            + "--destination <absolute-new-root> --seed-file <absolute-owner-only-file>");
        }
        String action = args[0];
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String flag = args[index];
            if (!Set.of("--destination", "--seed-file").contains(flag)
                    || options.put(flag, args[index + 1]) != null) {
                throw new IllegalArgumentException("unknown or duplicate generator option: " + flag);
            }
        }
        return new Parsed(action,
                Path.of(require(options, "--destination")),
                Path.of(require(options, "--seed-file")));
    }

    private static String readPrivateSeed(Path raw) throws IOException {
        if (!raw.isAbsolute() || !raw.normalize().equals(raw)
                || Files.isSymbolicLink(raw)
                || !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)
                || !raw.toRealPath().equals(raw)) {
            throw new IOException("seed file must be an absolute canonical non-symlink regular file");
        }
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(
                raw, LinkOption.NOFOLLOW_LINKS);
        if (!mode.contains(PosixFilePermission.OWNER_READ)
                || mode.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("seed file must be owner-only");
        }
        String seed = Files.readString(raw).strip();
        if (seed.indexOf('\n') >= 0 || seed.indexOf('\r') >= 0) {
            throw new IOException("seed file must contain exactly one seed line");
        }
        return seed;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required generator option: " + name);
        }
        return value;
    }

    private record Parsed(String action, Path destination, Path seedFile) {
    }
}
