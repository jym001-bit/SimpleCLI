package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunnerArtifactPolicyTest {
    private static final List<String> REQUIRED_CLASSES = List.of(
            "com/paicli/eval/benchmark/BenchmarkRelayWorkerMain.class",
            "com/paicli/eval/benchmark/BenchmarkRelayWorkerMain$FrozenRuntime.class",
            "com/paicli/eval/benchmark/BenchmarkToolRegistry.class",
            "com/paicli/eval/benchmark/BenchmarkToolProfile.class",
            "com/paicli/eval/benchmark/relay/BenchmarkFramedChannel.class",
            "com/paicli/eval/benchmark/relay/BenchmarkProviderRelay.class",
            "com/paicli/eval/benchmark/relay/BenchmarkRelayProtocol.class",
            "com/paicli/eval/benchmark/relay/BenchmarkRelayProtocol$Frame.class",
            "com/paicli/eval/benchmark/relay/RelayLlmClient.class",
            "com/paicli/eval/benchmark/relay/RelayMcpTransport.class",
            "com/paicli/eval/benchmark/relay/RelayWebDependencies.class",
            "com/paicli/eval/benchmark/relay/RelayHitlHandler.class",
            "com/paicli/eval/benchmark/relay/ScriptedInteraction.class",
            "com/paicli/eval/benchmark/relay/RelayWireConversions.class");

    @Test
    void opensSyntheticJarAndProducesOrderAndTimestampIndependentInventorySha(@TempDir Path tempDir)
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        Path first = writeJar(tempDir.resolve("first.jar"), entries, false, 1_000L,
                BenchmarkRunnerArtifactPolicy.MAIN_CLASS);
        Path second = writeJar(tempDir.resolve("second.jar"), entries, true, 9_000L,
                BenchmarkRunnerArtifactPolicy.MAIN_CLASS);

        BenchmarkRunnerArtifactPolicy.Inspection a = BenchmarkRunnerArtifactPolicy.inspect(first);
        BenchmarkRunnerArtifactPolicy.Inspection b = BenchmarkRunnerArtifactPolicy.inspect(second);

        assertEquals(a.inventorySha256(), b.inventorySha256());
        assertEquals(64, a.inventorySha256().length());
        assertEquals(a.entries(), b.entries());
        assertTrue(a.entries().contains("META-INF/MANIFEST.MF"));
    }

    @Test
    void rejectsMissingEntryAndWrongManifestMain(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> missing = validEntries();
        missing.remove("com/paicli/eval/benchmark/relay/RelayLlmClient.class");
        IOException missingError = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.inspect(writeJar(
                        tempDir.resolve("missing.jar"), missing, false, 1_000L,
                        BenchmarkRunnerArtifactPolicy.MAIN_CLASS)));
        assertTrue(missingError.getMessage().contains("missing required classes"));

        IOException mainError = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.inspect(writeJar(
                        tempDir.resolve("wrong-main.jar"), validEntries(), false, 1_000L,
                        "com.paicli.cli.Main")));
        assertTrue(mainError.getMessage().contains("Main-Class"));
    }

    @Test
    void rejectsDuplicateTraversalAndForbiddenProductEntries(@TempDir Path tempDir) throws Exception {
        List<String> names = new ArrayList<>(validEntries().keySet());
        names.add("META-INF/MANIFEST.MF");
        names.add("META-INF/MANIFEST.MF");
        IOException duplicateError = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.validateEntryNames(names));
        assertTrue(duplicateError.getMessage().contains("duplicate jar entry"));

        Map<String, byte[]> traversal = validEntries();
        traversal.put("com/paicli/eval/benchmark/relay/../../agent/Agent.class", new byte[]{1});
        IOException traversalError = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.inspect(writeJar(
                        tempDir.resolve("traversal.jar"), traversal, false, 1_000L,
                        BenchmarkRunnerArtifactPolicy.MAIN_CLASS)));
        assertTrue(traversalError.getMessage().contains("path-escaping"));

        Map<String, byte[]> forbidden = validEntries();
        forbidden.put("com/paicli/llm/DeepSeekClient.class", new byte[]{2});
        IOException forbiddenError = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.inspect(writeJar(
                        tempDir.resolve("provider.jar"), forbidden, false, 1_000L,
                        BenchmarkRunnerArtifactPolicy.MAIN_CLASS)));
        assertTrue(forbiddenError.getMessage().contains("forbidden product class"));
    }

    @Test
    void rejectsPlainTextAndSymbolicLinkArtifacts(@TempDir Path tempDir) throws Exception {
        Path plainText = Files.writeString(tempDir.resolve("plain.jar"), "not a jar\n")
                .toAbsolutePath().normalize();
        assertThrows(IOException.class, () -> BenchmarkRunnerArtifactPolicy.inspect(plainText));

        Path runner = writeJar(tempDir.resolve("runner.jar"), validEntries(), false, 1_000L,
                BenchmarkRunnerArtifactPolicy.MAIN_CLASS);
        Path link = tempDir.resolve("runner-link.jar").toAbsolutePath().normalize();
        Files.createSymbolicLink(link, runner);
        IOException error = assertThrows(IOException.class,
                () -> BenchmarkRunnerArtifactPolicy.inspect(link));
        assertTrue(error.getMessage().contains("non-symlink"));
    }

    private static Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < REQUIRED_CLASSES.size(); index++) {
            entries.put(REQUIRED_CLASSES.get(index), new byte[]{(byte) index, (byte) (index + 1)});
        }
        return entries;
    }

    private static Path writeJar(Path path,
                                 Map<String, byte[]> source,
                                 boolean reverse,
                                 long timestamp,
                                 String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(source.entrySet());
        if (reverse) {
            Collections.reverse(entries);
        }
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            for (Map.Entry<String, byte[]> item : entries) {
                JarEntry entry = new JarEntry(item.getKey());
                entry.setTime(timestamp);
                jar.putNextEntry(entry);
                jar.write(item.getValue());
                jar.closeEntry();
            }
        }
        return path.toAbsolutePath();
    }
}
