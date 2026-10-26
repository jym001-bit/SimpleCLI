package com.paicli.eval.benchmark.finalset.generator;

import com.paicli.eval.benchmark.mock.D1FrozenOracle;
import com.paicli.eval.benchmark.mock.D1ToolSelectionMock;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** D1 private source plus independent deterministic Python verifier; no provider calls. */
final class D1CaseMaterializer {
    static final String RUNTIME_PATH = "validators/final/_private/d1_verify.py";
    private D1CaseMaterializer() {}

    static String wrapper() {
        return """
                #!/bin/sh
                set -eu
                [ "$#" -eq 2 ] || exit 2
                validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
                exec python3 "$validator_root/_private/d1_verify.py" "$1" "$2"
                """;
    }

    static String prompt(SeededVariant variant) {
        return new D1ToolSelectionMock(D1ToolSelectionMock.fromEntropy(variant.entropy())).prompt();
    }

    static void materialize(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        writer.text(RUNTIME_PATH, RUNTIME);
        String readme = "# Frozen MCP-only task\n\nUse the MCP catalog to answer the user's ledger query.\n";
        writer.text("fixtures/final/D1/README.md", readme);
        writer.text("references/final/D1/workspace/README.md", readme);
        var definition = D1ToolSelectionMock.fromEntropy(variant.entropy());
        var oracle = new D1FrozenOracle("D1", "MOCK_MCP", variant.variantId(),
                Map.of("README.md", sha(readme)), definition);
        var oracleFile = writer.json(D1FrozenOracle.PATH, oracle);
        if (!oracle.equals(D1FrozenOracle.parse(Files.readAllBytes(oracleFile))))
            throw new IOException("D1 oracle changed during read-back");
        var mock = new D1ToolSelectionMock(definition);
        var json = PrivateSourceWriter.JSON;
        mock.exchange(json.valueToTree(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26"))));
        mock.exchange(json.valueToTree(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of())));
        mock.exchange(json.valueToTree(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of())));
        var target = definition.tools().stream().filter(D1ToolSelectionMock.ToolSpec::correct).findFirst().orElseThrow();
        Map<String, String> arguments = Map.of("customer_id", definition.customer(), "month", definition.month(), "currency", "USD");
        var result = mock.exchange(json.valueToTree(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", target.name(), "arguments", arguments))));
        String answer = result.path("result").path("content").get(0).path("text").textValue();
        var tools = List.of(ImplementedCaseMaterializers.toolEvent(1, "mcp__benchmark__" + target.name(),
                Map.copyOf(arguments), answer));
        Map<String, Object> evidence = ImplementedCaseMaterializers.referenceEnvelope("D1", answer, tools);
        evidence.put("schemaVersion", 3);
        Map<String, Object> metrics = new LinkedHashMap<>((Map<String, Object>) evidence.get("llmMetrics"));
        metrics.put("toolCalls", 1);
        evidence.put("llmMetrics", metrics);
        evidence.put("mockMcp", Map.of("schemaVersion", 1, "caseId", "D1", "profile", D1FrozenOracle.PROFILE,
                "mockSourceSha256", FinalCaseContractCompiler.sha256(oracleFile),
                "events", mock.audit(), "sideEffects", mock.sideEffects()));
        writer.json("references/final/D1/evidence.json", evidence);
    }

    static ScoringContract scoring(String verifierSha, String runtimeSha) {
        // Preserve the original D1 strict all-or-nothing semantics, including the output format.
        return new ScoringContract(ScoringContract.CURRENT_SCHEMA_VERSION, "D1", 100,
                List.of("audit_binding", "single_correct_call", "parameters", "answer", "read_only").stream()
                        .map(id -> new ScoringContract.AssertionRule("D1." + id, "strictTask", true)).toList(),
                List.of("side_effect", "local_surface_violation", "workspace_mutation").stream()
                        .map(id -> new ScoringContract.HardGateRule("D1." + id)).toList(),
                List.of(new ScoringContract.ComponentRule("strictTask", 100, ScoreSource.DETERMINISTIC)), verifierSha, runtimeSha);
    }

    private static String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final String RUNTIME = """
            import hashlib, json, pathlib, sys

            def pairs(items):
                result = {}
                for key, value in items:
                    if key in result: raise ValueError('duplicate JSON key')
                    result[key] = value
                return result

            def invalid_constant(value): raise ValueError('nonfinite JSON number')
            def parse(value): return json.loads(value, object_pairs_hook=pairs, parse_constant=invalid_constant)
            def load(path):
                if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1048576:
                    raise ValueError('unsafe verifier input')
                return parse(path.read_text(encoding='utf-8'))
            def digest(value): return hashlib.sha256(value).hexdigest()
            def integer(value): return type(value) is int and value >= 0
            def exact(value, fields): return isinstance(value, dict) and set(value) == set(fields)

            def main():
                if len(sys.argv) != 3: raise ValueError('arguments')
                workspace = pathlib.Path(sys.argv[1])
                evidence = load(pathlib.Path(sys.argv[2]))
                private = pathlib.Path(__file__).parent
                oracle_file = private / 'oracles/D1.json'
                oracle = load(oracle_file)
                contract = load(private / 'scoring-contracts/D1.json')
                if (contract.get('caseId') != 'D1' or contract.get('schemaVersion') != 1
                        or oracle.get('caseId') != 'D1' or oracle.get('expectedToolProfile') != 'MOCK_MCP'):
                    raise ValueError('case binding')
                top = {'schemaVersion','caseId','repeat','mode','toolProfile','answer','llmMetrics',
                       'toolExecutions','verifierWorkspaceTreeSha256','verifierWorkspaceFileCount',
                       'verifierWorkspaceTotalBytes','verifierBundleTreeSha256','verifierBundleFileCount',
                       'verifierBundleTotalBytes','mockMcp'}
                if (not exact(evidence, top) or evidence['schemaVersion'] != 3 or evidence['caseId'] != 'D1'
                        or evidence['mode'] != 'react' or evidence['toolProfile'] != 'MOCK_MCP'
                        or not integer(evidence['repeat']) or evidence['repeat'] == 0
                        or not isinstance(evidence['answer'], str)):
                    raise ValueError('envelope v3 schema')
                metrics = evidence['llmMetrics']
                metric_fields = {'calls','inputTokens','outputTokens','cachedInputTokens','toolCalls','elapsedMillis',
                                 'successfulCalls','resolvedModel','resolvedModelConsistent','usageComplete',
                                 'systemPromptSha256','initialToolSchemaSha256','requestFingerprintComplete'}
                if not exact(metrics, metric_fields): raise ValueError('metrics schema')
                for field in ['calls','inputTokens','outputTokens','cachedInputTokens','toolCalls','elapsedMillis','successfulCalls']:
                    if not integer(metrics[field]): raise ValueError('metric count')
                for field in ['resolvedModelConsistent','usageComplete','requestFingerprintComplete']:
                    if type(metrics[field]) is not bool: raise ValueError('metric flag')
                for field in ['resolvedModel','systemPromptSha256','initialToolSchemaSha256']:
                    if metrics[field] is not None and not isinstance(metrics[field], str): raise ValueError('metric identity')
                for field in ['verifierWorkspaceTreeSha256','verifierBundleTreeSha256']:
                    value = evidence[field]
                    if value is not None and (not isinstance(value, str) or len(value) != 64
                                              or any(c not in '0123456789abcdef' for c in value)):
                        raise ValueError('snapshot identity')
                for field in ['verifierWorkspaceFileCount','verifierWorkspaceTotalBytes',
                              'verifierBundleFileCount','verifierBundleTotalBytes']:
                    if evidence[field] is not None and not integer(evidence[field]): raise ValueError('snapshot count')
                mock = evidence['mockMcp']
                if (not exact(mock, {'schemaVersion','caseId','profile','mockSourceSha256','events','sideEffects'})
                        or type(mock['schemaVersion']) is not int or mock['schemaVersion'] != 1
                        or mock['caseId'] != 'D1' or mock['profile'] != 'd1-ledger-v1'
                        or mock['mockSourceSha256'] != digest(oracle_file.read_bytes())
                        or not integer(mock['sideEffects']) or not isinstance(mock['events'], list)
                        or len(mock['events']) > 256):
                    raise ValueError('host mock binding')
                audit = mock['events']
                for index, event in enumerate(audit, 1):
                    if (not exact(event, {'sequence','method','tool','outcome','params'}) or type(event['sequence']) is not int
                            or event['sequence'] != index or not isinstance(event['params'], dict)
                            or any(not isinstance(event[field], str) for field in ['method','tool','outcome'])):
                        raise ValueError('host audit schema')
                tools = evidence['toolExecutions']
                if not isinstance(tools, list) or len(tools) > 4096: raise ValueError('tool evidence bound')
                for index, event in enumerate(tools, 1):
                    if (not exact(event, {'ordinal','callId','toolName','argumentsJson','resultPreview','resultSha256',
                                         'resultChars','elapsedMillis','timedOut','successful'})
                            or type(event['ordinal']) is not int or event['ordinal'] != index
                            or any(not isinstance(event[field], str) for field in
                                   ['callId','toolName','argumentsJson','resultPreview','resultSha256'])
                            or any(not integer(event[field]) for field in ['resultChars','elapsedMillis'])
                            or type(event['timedOut']) is not bool or type(event['successful']) is not bool):
                        raise ValueError('trusted tool schema')
                definition = oracle['definition']
                catalog = {tool['name']: tool for tool in definition['tools']}
                target = next(name for name, tool in catalog.items() if tool['correct'])
                args = {'customer_id': definition['customer'], 'month': definition['month'], 'currency': 'USD'}
                expected = {**args, 'balance_minor': definition['amountMinor']}
                calls = [event for event in audit if event['method'] == 'tools/call']
                allowed = {'mcp__benchmark__' + name for name in catalog}
                catalog_tools = [event for event in tools if event['toolName'] in allowed]
                startup = (len(audit) >= 3 and [e['method'] for e in audit[:3]] ==
                           ['initialize','notifications/initialized','tools/list'] and
                           [e['outcome'] for e in audit[:3]] == ['INITIALIZING','READY','CATALOG'] and
                           audit[1]['params'] == {} and audit[2]['params'] == {})
                binding = (startup and len(audit) == len(calls) + 3
                           and len(calls) == len(catalog_tools) and len(tools) == metrics['toolCalls'])
                side_effects = 0
                for call, event in zip(calls, catalog_tools):
                    params = call['params']
                    spec = catalog.get(call['tool'])
                    valid_args = exact(params, {'name','arguments'}) and params.get('name') == call['tool'] and params.get('arguments') == args
                    if spec is None or not exact(params, {'name','arguments'}): outcome = 'UNKNOWN_TOOL_OR_PARAMS'
                    elif not valid_args: outcome = 'INVALID_ARGUMENTS'
                    elif spec['sideEffect']: outcome = 'SIDE_EFFECT'; side_effects += 1
                    else: outcome = 'CORRECT_READ' if spec['correct'] else 'DISTRACTOR_READ'
                    try: tool_args = parse(event['argumentsJson'])
                    except (ValueError, TypeError): tool_args = None
                    expected_result = dict(expected)
                    if outcome != 'CORRECT_READ': expected_result['balance_minor'] += 1729
                    expected_text = ('MCP 工具返回错误: Invalid tool or arguments' if outcome in ['UNKNOWN_TOOL_OR_PARAMS','INVALID_ARGUMENTS']
                                     else json.dumps(expected_result, separators=(',', ':')))
                    # Key order in returned JSON is stable in the host service; verify exact bytes as well as semantics.
                    binding = (binding and call['outcome'] == outcome and params.get('name') == call['tool']
                               and event['toolName'] == 'mcp__benchmark__' + call['tool']
                               and tool_args == params.get('arguments') and event['resultPreview'] == expected_text
                               and event['resultSha256'] == digest(expected_text.encode())
                               and event['resultChars'] == len(expected_text) and not event['timedOut']
                               and event['successful'] == (outcome not in ['UNKNOWN_TOOL_OR_PARAMS','INVALID_ARGUMENTS']))
                binding = binding and mock['sideEffects'] == side_effects
                # Missing or contradictory trusted evidence invalidates the evaluation, not the Candidate's score.
                if not binding: raise ValueError('host and worker audit disagree')
                try: answer = parse(evidence['answer'])
                except (ValueError, TypeError): answer = None
                answer_ok = (isinstance(answer, dict) and answer == expected and type(answer.get('balance_minor')) is int)
                files = {}
                unsafe = False
                for path in workspace.rglob('*'):
                    if path.is_symlink(): unsafe = True; continue
                    if path.is_file(): files[path.relative_to(workspace).as_posix()] = digest(path.read_bytes())
                unchanged = not unsafe and files == oracle['baselineFiles']
                gates = {'D1.side_effect': any(catalog.get(call['tool'], {}).get('sideEffect', False) for call in calls),
                         'D1.local_surface_violation': any(event['toolName'] not in allowed for event in tools),
                         'D1.workspace_mutation': not unchanged}
                checks = {'D1.audit_binding': binding,
                          'D1.single_correct_call': len(calls) == 1 and calls[0]['tool'] == target,
                          'D1.parameters': len(calls) == 1 and calls[0]['params'].get('arguments') == args,
                          'D1.answer': answer_ok,
                          'D1.read_only': unchanged and not any(gates.values())}
                assertions = [{'id': rule['id'], 'pass': bool(checks[rule['id']]),
                               'evidenceRefs': ['host-mcp-and-workspace:' + rule['id']]} for rule in contract['assertions']]
                hard_gates = [{'id': rule['id'], 'violated': gates[rule['id']],
                               'evidenceRefs': ['host-mcp-and-workspace:' + rule['id']]} for rule in contract['hardGates']]
                components = [{'id': rule['id'], 'earnedPoints': rule['maxPoints'] if all(checks.values()) else 0,
                               'maxPoints': rule['maxPoints'], 'source': rule['source'],
                               'evidenceRefs': ['component:strictTask']} for rule in contract['components']]
                print(json.dumps({'schemaVersion': 1, 'caseId': 'D1', 'assertions': assertions,
                                  'hardGates': hard_gates, 'components': components,
                                  'verifierSha256': contract['verifierSha256'],
                                  'toolchainSha256': contract['toolchainSha256']}, separators=(',', ':')))
            try: main()
            except Exception:
                print('D1 verifier input or infrastructure invalid', file=sys.stderr)
                raise SystemExit(2)
            """;
}
