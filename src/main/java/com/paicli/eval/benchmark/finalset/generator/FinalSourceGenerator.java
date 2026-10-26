package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.CaseDefinition;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Deterministically materializes private final-source sibling variants outside every Git tree.
 *
 * <p>The currently implemented operation is intentionally named {@link #generateIncompleteSource}:
 * it creates all 28 recipe skeletons and reference-report prototype
 * A1-A4/B1-B6/C1-C3/D1-D4/G1-G2 materials,
 * but omits {@code suite.json} and writes an incomplete marker. {@link #generateFinalSource}
 * refuses before touching disk until all 28 recipes are implemented.</p>
 */
public final class FinalSourceGenerator {
    public static final String GENERATION_MANIFEST = "generation-manifest.json";
    public static final String DRAFT_SUITE = "suite.draft.json";
    public static final String INCOMPLETE_MARKER = ".source-generation-incomplete";
    public static final String DATASET_CANARY = ".private-final-dataset-canary";
    private static final String SUITE_ID = "paicli-native-agentbench-v0.1-final";
    private static final String BLUEPRINT_VERSION = "0.1";
    private static final Pattern PRIVATE_SEED = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|Bearer\\s+[A-Za-z0-9._~-]{12,}|"
                    + "(?:sk|ak)-[A-Za-z0-9_-]{16,}|(?:api[_-]?key|access[_-]?token)\\s*[=:]\\s*[^\\s]{8,})");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Produces the current auditable partial source. It can never be consumed as a formal suite. */
    public GenerationResult generateIncompleteSource(GenerationRequest request) throws IOException {
        ValidatedRequest validated = validate(request);
        Files.createDirectory(validated.destination(),
                PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
        boolean complete = false;
        try {
            PrivateSourceWriter writer = new PrivateSourceWriter(validated.destination());
            writer.text(DATASET_CANARY, "PAICLI-FINAL-CANARY-v1:"
                    + deriveHex(validated.seed(), "dataset-canary") + "\n");
            writer.text(INCOMPLETE_MARKER,
                    "PAICLI private final source is NOT executable as a 28-case suite.\n"
                            + "Missing case recipes: " + String.join(",", FinalSourceRecipeCatalog.missingIds())
                            + "\nDo not rename suite.draft.json to suite.json.\n");
            ImplementedCaseMaterializers.writeSharedVerifierRuntime(writer);

            Map<String, SeededVariant> variants = new LinkedHashMap<>();
            Map<String, String> renderedPrompts = new LinkedHashMap<>();
            for (FinalSourceRecipeCatalog.Recipe recipe : FinalSourceRecipeCatalog.recipes()) {
                SeededVariant variant = SeededVariant.derive(
                        validated.seed(), recipe.id(), recipe.recipeVersion());
                variants.put(recipe.id(), variant);
                String renderedPrompt = ImplementedCaseMaterializers.renderPublicPrompt(
                        recipe, variant);
                renderedPrompts.put(recipe.id(), renderedPrompt);
                writeRecipeSkeleton(writer, recipe, variant, renderedPrompt);
                if (recipe.status() == FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED) {
                    ImplementedCaseMaterializers.materialize(writer, recipe, variant);
                    writer.json(FinalCaseContractCompiler.contractPath(recipe.id()),
                            FinalCaseContractCompiler.compile(writer.root(), recipe.id()));
                }
            }
            Map<String, FinalCaseContractCompiler.Limits> executionLimits = new LinkedHashMap<>();
            for (String caseId : FinalSourceRecipeCatalog.implementedIds())
                executionLimits.put(caseId, FinalCaseContractCompiler.limits(caseId));
            writer.json("provenance/execution-policy.json", Map.of(
                    "version", FinalCaseContractCompiler.POLICY_VERSION,
                    "allProvidersShareCaseLimits", true,
                    "limits", executionLimits));
            writer.json(DRAFT_SUITE, draftSuite(variants, renderedPrompts));

            List<FinalSourceGenerationManifest.CaseRecipe> manifestedCases = new ArrayList<>();
            for (FinalSourceRecipeCatalog.Recipe recipe : FinalSourceRecipeCatalog.recipes()) {
                SeededVariant variant = variants.get(recipe.id());
                boolean implemented = recipe.status()
                        == FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED;
                String maturity = implemented ? switch (recipe.id()) {
                    case "B5" -> "REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_CONCURRENCY";
                    case "C1" -> "REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_TOOLCHAIN";
                    case "C2" -> "REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_PROCESS_LIFECYCLE";
                    case "C3" -> "REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_COMMAND_PROVENANCE";
                    case "E1" -> "REFERENCE_REPORT_PROTOTYPE_PLAN_BOUND_CONTROL";
                    case "E2" -> "REFERENCE_REPORT_PROTOTYPE_TEAM_BOUND_CONTROL";
                    default -> "REFERENCE_REPORT_PROTOTYPE";
                } : "RECIPE_ONLY";
                String blocker = implemented
                        ? "full-28-case-suite-contract-not-assembled;"
                        + "formal-runner-not-consuming-reference-prototype"
                        + ("B5".equals(recipe.id())
                        ? ";deterministic-concurrency-scheduler-not-frozen" : "")
                        + ("C1".equals(recipe.id())
                        ? ";maven-worker-image-and-readonly-cache-not-frozen" : "")
                        + ("C2".equals(recipe.id())
                        ? ";process-port-namespace-and-lifecycle-evidence-not-integrated" : "")
                        + ("C3".equals(recipe.id())
                        ? ";terminal-artifact-provenance-not-integrated" : "")
                        + ("E1".equals(recipe.id())
                        ? ";remaining-plan-failure-semantics-not-closed;reference-is-synthetic-not-provider-evidence" : "")
                        + ("E2".equals(recipe.id())
                        ? ";command-provenance-not-integrated;reference-is-synthetic-not-provider-evidence" : "")
                        + (List.of("A3", "A4").contains(recipe.id())
                        ? ";judge-calibration-not-integrated" : "")
                        : recipe.failClosedReason();
                manifestedCases.add(new FinalSourceGenerationManifest.CaseRecipe(
                        recipe.id(), recipe.title(), recipe.category(), recipe.level().name(),
                        recipe.weight(), recipe.mode().name().toLowerCase(Locale.ROOT),
                        recipe.toolProfile(), recipe.verifierProfile(), recipe.recipeVersion(),
                        recipe.status().name(),
                        maturity,
                        "NOT_INTEGRATED",
                        false,
                        variant.variantId(),
                        caseTreeDigest(writer.root(), recipe.id()),
                        "prompts/final/" + recipe.id() + ".md",
                        "fixtures/final/" + recipe.id(),
                        implemented ? "validators/final/" + recipe.id() : "",
                        implemented ? "validators/final/_private/oracles/" + recipe.id() + ".json" : "",
                        implemented ? "validators/final/_private/scoring-contracts/"
                                + recipe.id() + ".json" : "",
                        implemented ? FinalCaseContractCompiler.contractPath(recipe.id()) : "",
                        implemented ? FinalCaseContractCompiler.sha256(writer.root().resolve(
                                FinalCaseContractCompiler.contractPath(recipe.id()))) : "",
                        implemented ? "references/final/" + recipe.id() + "/workspace" : "",
                        blocker));
            }
            String payloadDigest = treeDigest(writer.root(), path ->
                    !portable(writer.root(), path).equals(GENERATION_MANIFEST));
            FinalSourceGenerationManifest manifest = new FinalSourceGenerationManifest(
                    FinalSourceGenerationManifest.CURRENT_VERSION,
                    FinalSourceGenerationManifest.FORMAT,
                    SUITE_ID,
                    BLUEPRINT_VERSION,
                    deriveHex(validated.seed(), "seed-fingerprint"),
                    false,
                    FinalSourceRecipeCatalog.REQUIRED_CASE_COUNT,
                    FinalSourceRecipeCatalog.implementedIds().size(),
                    FinalSourceRecipeCatalog.missingIds(),
                    payloadDigest,
                    manifestedCases);
            writer.json(GENERATION_MANIFEST, manifest);
            auditGeneratedTree(writer.root(), validated.seed());
            FinalSourceGenerationManifest reloaded = inspect(writer.root());
            if (!manifest.equals(reloaded)) {
                throw new IOException("generation manifest changed during read-back");
            }
            complete = true;
            return new GenerationResult(writer.root(), manifest,
                    treeDigest(writer.root(), ignored -> true));
        } finally {
            if (!complete) {
                deleteNewGenerationRoot(validated.destination());
            }
        }
    }

    /** Formal entry point: deliberately fail-closed while any of the 28 recipes is incomplete. */
    public GenerationResult generateFinalSource(GenerationRequest request) throws IOException {
        FinalSourceRecipeCatalog.requireFinalReady();
        throw new IllegalStateException(
                "all recipes are marked implemented but the final publication path is not enabled");
    }

    public FinalSourceGenerationManifest inspect(Path sourceRoot) throws IOException {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot must not be null");
        }
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path manifest = root.resolve(GENERATION_MANIFEST);
        if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("private source generation manifest is missing or unsafe");
        }
        try (InputStream input = Files.newInputStream(manifest, StandardOpenOption.READ)) {
            FinalSourceGenerationManifest loaded = STRICT_JSON.readValue(
                    input, FinalSourceGenerationManifest.class);
            validateManifest(loaded);
            String currentPayload = treeDigest(root,
                    path -> !portable(root, path).equals(GENERATION_MANIFEST));
            if (!currentPayload.equals(loaded.payloadTreeSha256())) {
                throw new IOException("private source payload digest does not match generation manifest");
            }
            for (var recipe : loaded.cases()) {
                if (recipe.caseContractPath().isEmpty()) continue;
                Path file = root.resolve(recipe.caseContractPath());
                if (!recipe.caseContractSha256().equals(FinalCaseContractCompiler.sha256(file))
                        || !FinalExecutableSuiteContract.CaseContract.load(file).equals(
                                FinalCaseContractCompiler.compile(root, recipe.id())))
                    throw new IOException("generated case contract binding changed: " + recipe.id());
            }
            return loaded;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid private source generation manifest", error);
        }
    }

    /** Rejects the current partial materialization instead of treating implemented cases as final. */
    public void requireFinalReady(Path sourceRoot) throws IOException {
        FinalSourceGenerationManifest manifest = inspect(sourceRoot);
        if (!manifest.finalReady() || !manifest.missingCaseIds().isEmpty()
                || Files.exists(sourceRoot.resolve(INCOMPLETE_MARKER), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(sourceRoot.resolve("suite.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "generated private source is not a complete 28-case executable final suite");
        }
    }

    private static ValidatedRequest validate(GenerationRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("generation request must not be null");
        }
        if (request.privateSeedHex() == null
                || !PRIVATE_SEED.matcher(request.privateSeedHex()).matches()) {
            throw new IllegalArgumentException(
                    "privateSeedHex must be exactly 256 bits as 64 lowercase hexadecimal characters");
        }
        if (request.destinationRoot() == null || !request.destinationRoot().isAbsolute()
                || !request.destinationRoot().normalize().equals(request.destinationRoot())) {
            throw new IllegalArgumentException(
                    "destinationRoot must be an absolute, already-normalized path");
        }
        Path destination = request.destinationRoot();
        Path parent = destination.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().equals(parent)) {
            throw new IOException("destination parent must be an existing canonical non-symlink directory");
        }
        Set<PosixFilePermission> parentMode = Files.getPosixFilePermissions(
                parent, LinkOption.NOFOLLOW_LINKS);
        if (!parentMode.contains(PosixFilePermission.OWNER_READ)
                || !parentMode.contains(PosixFilePermission.OWNER_WRITE)
                || !parentMode.contains(PosixFilePermission.OWNER_EXECUTE)
                || parentMode.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("destination parent must be owner-only mode 0700: " + parent);
        }
        rejectGitAncestor(parent);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(
                    "refusing to replace private final source: " + destination);
        }
        return new ValidatedRequest(destination, HexFormat.of().parseHex(request.privateSeedHex()));
    }

    private static void rejectGitAncestor(Path start) throws IOException {
        Path current = start;
        while (current != null) {
            Path marker = current.resolve(".git");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("private final source must remain outside every Git tree: " + current);
            }
            current = current.getParent();
        }
    }

    private static void writeRecipeSkeleton(PrivateSourceWriter writer,
                                            FinalSourceRecipeCatalog.Recipe recipe,
                                            SeededVariant variant,
                                            String renderedPrompt) throws IOException {
        writer.text("prompts/final/" + recipe.id() + ".md", "F3".equals(recipe.id()) ? renderedPrompt
                : "# " + recipe.id() + " " + recipe.title() + "\n\n"
                        + renderedPrompt + "\n\nVariant: " + variant.variantId() + "\n");
        // E1's closed input consists of exactly two CSV files. Keep generator metadata off its tool surface.
        writer.json((List.of("E1", "E2", "F1", "F2", "F3", "F4").contains(recipe.id()) ? "provenance/final/" : "fixtures/final/") + recipe.id() + "/CASE-METADATA.json", Map.of(
                "caseId", recipe.id(),
                "recipeVersion", recipe.recipeVersion(),
                "status", recipe.status().name(),
                "variantId", variant.variantId()));
        if (recipe.status() != FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED) {
            writer.text("fixtures/final/" + recipe.id() + "/UNIMPLEMENTED.txt",
                    "FAIL_CLOSED\ncase=" + recipe.id() + "\nvariant=" + variant.variantId()
                            + "\nmissingCapability=" + recipe.failClosedReason() + "\n");
        }
    }

    private static DraftSuite draftSuite(Map<String, SeededVariant> variants,
                                         Map<String, String> renderedPrompts) {
        List<DraftCase> cases = FinalSourceRecipeCatalog.recipes().stream().map(recipe -> {
            boolean implemented = recipe.status()
                    == FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED;
            List<String> verifier = implemented
                    ? List.of("validators/final/" + recipe.id(),
                    CaseDefinition.WORKSPACE_PLACEHOLDER, CaseDefinition.EVIDENCE_PLACEHOLDER)
                    : List.of();
            return new DraftCase(
                    recipe.id(), recipe.title(), recipe.category(), recipe.level().name(), 0,
                    recipe.mode().name().toLowerCase(Locale.ROOT),
                    "fixtures/final/" + recipe.id(), "F3".equals(recipe.id()) ? renderedPrompts.get(recipe.id())
                    : renderedPrompts.get(recipe.id()) + " Variant: " + variants.get(recipe.id()).variantId(),
                    implemented ? "command" : "none", verifier, "draft");
        }).toList();
        return new DraftSuite("0.1-final-source-incomplete", "PaiCLI Native AgentBench private final source draft",
                cases);
    }

    private static void validateManifest(FinalSourceGenerationManifest manifest) {
        if (manifest == null
                || manifest.manifestVersion() != FinalSourceGenerationManifest.CURRENT_VERSION
                || !FinalSourceGenerationManifest.FORMAT.equals(manifest.format())
                || !SUITE_ID.equals(manifest.suiteId())
                || !BLUEPRINT_VERSION.equals(manifest.blueprintVersion())
                || manifest.finalReady()
                || manifest.recipeCount() != FinalSourceRecipeCatalog.REQUIRED_CASE_COUNT
                || manifest.implementedRecipeCount() != FinalSourceRecipeCatalog.implementedIds().size()
                || !manifest.missingCaseIds().equals(FinalSourceRecipeCatalog.missingIds())
                || manifest.cases().size() != FinalSourceRecipeCatalog.REQUIRED_CASE_COUNT
                || !manifest.cases().stream().map(FinalSourceGenerationManifest.CaseRecipe::id).toList()
                .equals(FinalSourceRecipeCatalog.recipes().stream()
                        .map(FinalSourceRecipeCatalog.Recipe::id).toList())) {
            throw new IllegalArgumentException("generation manifest does not match the 28-case recipe catalog");
        }
        for (var item : manifest.cases()) {
            var recipe = FinalSourceRecipeCatalog.require(item.id());
            boolean implemented = recipe.status() == FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED;
            if (item.weight() != recipe.weight() || !item.category().equals(recipe.category())
                    || !item.level().equals(recipe.level().name())
                    || !item.mode().equals(recipe.mode().name().toLowerCase(Locale.ROOT))
                    || !item.toolProfile().equals(recipe.toolProfile())
                    || !item.implementationStatus().equals(recipe.status().name())
                    || !"NOT_INTEGRATED".equals(item.runnerIntegrationStatus()) || item.publicationEligible()
                    || !(implemented ? FinalCaseContractCompiler.contractPath(item.id()) : "")
                    .equals(item.caseContractPath())
                    || !(implemented ? item.caseContractSha256().matches("[0-9a-f]{64}")
                    : item.caseContractSha256().isEmpty()))
                throw new IllegalArgumentException("generation case metadata differs from recipe catalog");
        }
    }

    private static void auditGeneratedTree(Path root, byte[] seed) throws IOException {
        Set<Object> fileKeys = new HashSet<>();
        String rawSeed = HexFormat.of().formatHex(seed);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir) || attrs.isSymbolicLink() || !attrs.isDirectory()) {
                    throw new IOException("generated source contains an unsafe directory: " + dir);
                }
                requireOwnerOnly(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    throw new IOException("generated source contains a link or special file: " + file);
                }
                requireOwnerOnly(file);
                Object links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (!(links instanceof Number number) || number.longValue() != 1L) {
                    throw new IOException("generated source contains a hardlink: " + file);
                }
                Object key = attrs.fileKey();
                if (key == null || !fileKeys.add(key)) {
                    throw new IOException("generated source file identity is missing or reused: " + file);
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains(rawSeed)) {
                    throw new IOException("private generation seed was written into source payload: " + file);
                }
                if (!file.getFileName().toString().equals(DATASET_CANARY)
                        && CREDENTIAL_LIKE.matcher(text).find()) {
                    throw new IOException("credential-like payload is forbidden in generated source: " + file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void requireOwnerOnly(Path path) throws IOException {
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (mode.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("generated source is not owner-only: " + path);
        }
    }

    private static String caseTreeDigest(Path root, String caseId) throws IOException {
        List<String> prefixes = List.of(
                "fixtures/final/" + caseId + "/",
                "prompts/final/" + caseId + ".md",
                "validators/final/" + caseId,
                "validators/final/_private/oracles/" + caseId + ".json",
                "validators/final/_private/scoring-contracts/" + caseId + ".json",
                "validators/final/_private/hidden/" + caseId + "/",
                "provenance/final/" + caseId + "/",
                "references/final/" + caseId + "/");
        return treeDigest(root, path -> {
            String relative = portable(root, path);
            return prefixes.stream().anyMatch(prefix -> relative.equals(prefix)
                    || relative.startsWith(prefix));
        });
    }

    private static String treeDigest(Path root, Predicate<Path> include) throws IOException {
        MessageDigest digest = sha256Digest();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(include)
                    .sorted(Comparator.comparing(path -> portable(root, path)))
                    .toList();
        }
        for (Path file : files) {
            String relative = portable(root, file);
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(sha256Bytes(file));
            digest.update((byte) 0);
            digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(mode(file).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    }

    private static byte[] sha256Bytes(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static String deriveHex(byte[] seed, String domain) {
        MessageDigest digest = sha256Digest();
        digest.update(("paicli-final-source/v1/" + domain + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(seed);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String portable(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static void deleteNewGenerationRoot(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to clean an unsafe generation root: " + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    throw new IOException("refusing to clean unsafe generated entry: " + file);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record GenerationRequest(Path destinationRoot, String privateSeedHex) {
    }

    public record GenerationResult(Path sourceRoot,
                                   FinalSourceGenerationManifest manifest,
                                   String completeTreeSha256) {
    }

    private record ValidatedRequest(Path destination, byte[] seed) {
    }

    private record DraftSuite(String version, String name, List<DraftCase> cases) {
    }

    private record DraftCase(String id,
                             String title,
                             String category,
                             String level,
                             int weight,
                             String mode,
                             String fixturePath,
                             String prompt,
                             String verifierType,
                             List<String> verifierCommand,
                             String status) {
    }
}
