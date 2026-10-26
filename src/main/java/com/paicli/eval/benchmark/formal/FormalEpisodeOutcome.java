package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.scoring.ScoreCalculator;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed outcome taxonomy for a formal episode attempt.
 *
 * <p>Only {@link Scored} has a numeric score. Infrastructure, security, dataset and
 * evaluation-definition failures are deliberately non-numeric and therefore cannot silently
 * enter aggregation. The current formal runner still requires a separate publication gate.</p>
 */
public sealed interface FormalEpisodeOutcome permits
        FormalEpisodeOutcome.Scored,
        FormalEpisodeOutcome.Infra,
        FormalEpisodeOutcome.Security,
        FormalEpisodeOutcome.Dataset,
        FormalEpisodeOutcome.EvaluationDefect {

    AttemptKey key();

    default boolean publishable() {
        return false;
    }

    /** A valid verifier result, including a legitimate score of zero. */
    record Scored(AttemptKey key, ScoreCalculator.Result result)
            implements FormalEpisodeOutcome {
        public Scored {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(result, "result");
            if (result.score() == null
                    || result.failureClass() == ScoreCalculator.FailureClass.INFRA_UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "Scored requires a numeric non-infrastructure scoring result");
            }
        }

        public int score() {
            return result.scoreForAggregation();
        }
    }

    record Infra(AttemptKey key, InfraCode code, List<String> evidenceRefs)
            implements FormalEpisodeOutcome {
        public Infra {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(code, "code");
            evidenceRefs = safeEvidence(evidenceRefs);
        }
    }

    record Security(AttemptKey key, SecurityCode code, List<String> evidenceRefs)
            implements FormalEpisodeOutcome {
        public Security {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(code, "code");
            evidenceRefs = safeEvidence(evidenceRefs);
        }
    }

    record Dataset(AttemptKey key, DatasetCode code, List<String> evidenceRefs)
            implements FormalEpisodeOutcome {
        public Dataset {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(code, "code");
            evidenceRefs = safeEvidence(evidenceRefs);
        }
    }

    record EvaluationDefect(
            AttemptKey key,
            EvaluationDefectCode code,
            List<String> evidenceRefs) implements FormalEpisodeOutcome {
        public EvaluationDefect {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(code, "code");
            evidenceRefs = safeEvidence(evidenceRefs);
        }
    }

    enum InfraCode {
        PROVIDER_UNAVAILABLE,
        WORKER_UNAVAILABLE,
        VERIFIER_UNAVAILABLE,
        JUDGE_UNAVAILABLE,
        TIMEOUT,
        ARTIFACT_IO_FAILURE
    }

    enum SecurityCode {
        CREDENTIAL_CANARY_VIOLATION,
        PATH_OR_SYMLINK_VIOLATION,
        SANDBOX_BOUNDARY_VIOLATION,
        VERIFIER_BUNDLE_TAMPERED
    }

    enum DatasetCode {
        FIXTURE_DRIFT,
        VERIFIER_DEPENDENCY_DRIFT,
        VERIFIER_BUNDLE_DIGEST_MISMATCH,
        DATASET_CANARY_VIOLATION
    }

    enum EvaluationDefectCode {
        NON_CANONICAL_EPISODE,
        UNSUPPORTED_CASE,
        UNSUPPORTED_MODE,
        UNSUPPORTED_TOOL_PROFILE,
        UNSUPPORTED_JUDGE,
        UNSUPPORTED_MOCK,
        UNSUPPORTED_EPISODE_EVENTS,
        UNSUPPORTED_EVIDENCE,
        UNSUPPORTED_OUTPUT_CAP,
        CREDENTIAL_PROVIDER_MISMATCH,
        ENDPOINT_POLICY_MISMATCH,
        INVALID_VERIFIER_REPORT,
        PROVIDER_EVIDENCE_UNAVAILABLE,
        INVALID_RUNNER_HARD_GATE
    }

    private static List<String> safeEvidence(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("evidenceRefs must not be null");
        }
        List<String> copy;
        try {
            copy = List.copyOf(values);
        } catch (NullPointerException error) {
            throw new IllegalArgumentException("evidenceRefs must not contain null", error);
        }
        java.util.HashSet<String> unique = new java.util.HashSet<>();
        for (String value : copy) {
            if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9._:+/#-]{0,255}", value)
                    || value.startsWith("/") || value.contains("../") || value.contains("/..")) {
                throw new IllegalArgumentException("unsafe formal outcome evidence reference");
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        "duplicate formal outcome evidence reference: " + value);
            }
        }
        return copy;
    }
}
