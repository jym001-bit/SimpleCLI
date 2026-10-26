"""E1 envelope-v5 scoring adapter for the registered reference prototype.

Supports closed task traces and bounded local-fault replanning. Production
admission still needs complete-suite coverage and remaining failure semantics.
"""
import pathlib
import sys
from e1_replay import compact, digest, exact, integer, load, prompt, qualify, require, sha, same, source


def verify(oracle, evidence, workspace, contract, source_hash):
    source(oracle)
    require(integer(oracle['schemaVersion'], 2, 2), 'draft formal source v2')
    fields = {'schemaVersion', 'caseId', 'repeat', 'mode', 'toolProfile', 'answer', 'llmMetrics', 'toolExecutions',
              'verifierWorkspaceTreeSha256', 'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
              'verifierBundleTreeSha256', 'verifierBundleFileCount', 'verifierBundleTotalBytes', 'plan'}
    require(exact(evidence, fields) and integer(evidence['schemaVersion'], 5, 5)
            and evidence['caseId'] == 'E1' and evidence['mode'] == 'plan' and evidence['toolProfile'] == 'FILE_ONLY'
            and integer(evidence['repeat'], 1) and isinstance(evidence['answer'], str), 'draft envelope v5')
    metrics = evidence['llmMetrics']
    counts = {'calls', 'inputTokens', 'outputTokens', 'cachedInputTokens', 'toolCalls', 'elapsedMillis', 'successfulCalls'}
    flags = {'resolvedModelConsistent', 'usageComplete', 'requestFingerprintComplete'}
    strings = {'resolvedModel', 'systemPromptSha256', 'initialToolSchemaSha256'}
    require(exact(metrics, counts | flags | strings) and all(integer(metrics[k]) for k in counts)
            and all(metrics[k] is True for k in flags)
            and isinstance(metrics['resolvedModel'], str) and metrics['resolvedModel']
            and (metrics['systemPromptSha256'] is None or sha(metrics['systemPromptSha256']))
            and sha(metrics['initialToolSchemaSha256']), 'complete metrics shape')
    for scope in ('Workspace', 'Bundle'):
        identity = [evidence['verifier' + scope + suffix] for suffix in ('TreeSha256', 'FileCount', 'TotalBytes')]
        require(all(v is None for v in identity) or sha(identity[0]) and integer(identity[1]) and integer(identity[2]), 'snapshot identity tuple')
    plan = evidence['plan']
    require(exact(plan, {'schemaVersion', 'caseId', 'profile', 'relayVersion', 'sourceSha256', 'promptSha256',
                        'audit', 'scopedRequestFingerprints'}) and integer(plan['schemaVersion'], 1, 1)
            and plan['caseId'] == 'E1' and plan['profile'] == oracle['profile']
            and plan['promptSha256'] == digest(prompt(oracle)), 'host Plan source/prompt binding')
    replay = dict(schemaVersion=1, caseId='E1', profile=plan['profile'], relayVersion=plan['relayVersion'],
                  sourceSha256=plan['sourceSha256'], planAudit=plan['audit'],
                  scopedRequestFingerprints=plan['scopedRequestFingerprints'],
                  toolExecutions=evidence['toolExecutions'], modelToolCalls=metrics['toolCalls'])
    result = qualify(oracle, replay, workspace, source_hash)
    turns = plan['audit']['providerTurns']
    require(metrics['calls'] == metrics['successfulCalls'] == len(turns), 'unrepresented provider calls')
    for turn in turns:
        r = turn['response']
        require(r['usagePresent'] is True and r['resolvedModel'] == metrics['resolvedModel']
                and r['cachedInputTokens'] <= r['inputTokens'] and r['outputTokens'] <= 16384
                and r['inputTokens'] + r['outputTokens'] <= 1_000_000, 'response identity/usage/cap')
    for key in ('inputTokens', 'outputTokens', 'cachedInputTokens'):
        require(sum(t['response'][key] for t in turns) == metrics[key], 'usage aggregate')
    fingerprints = plan['scopedRequestFingerprints']['requests']
    require(metrics['initialToolSchemaSha256'] == fingerprints[0]['toolSchemaSha256'], 'initial schema identity')
    systems = {f['systemPromptSha256'] for f in fingerprints}
    require(metrics['systemPromptSha256'] == (next(iter(systems)) if len(systems) == 1 else None), 'system identity projection')
    checks = {'E1.' + k: v for k, v in result['checks'].items()}
    gates = {'E1.' + k: v for k, v in result['hardGates'].items()}
    require(exact(contract, {'schemaVersion', 'caseId', 'strictSuccessMinimum', 'assertions', 'hardGates', 'components',
                            'verifierSha256', 'toolchainSha256'}) and integer(contract['schemaVersion'], 1, 1)
            and contract['caseId'] == 'E1' and integer(contract['strictSuccessMinimum'], 100, 100)
            and sha(contract['verifierSha256']) and sha(contract['toolchainSha256']), 'scoring identity')
    require(same(contract['assertions'], [{'id': k, 'componentId': 'strictTask', 'mandatory': True} for k in checks])
            and same(contract['hardGates'], [{'id': k} for k in gates])
            and same(contract['components'], [{'id': 'strictTask', 'maxPoints': 100, 'source': 'DETERMINISTIC'}]), 'scoring policy')
    return dict(schemaVersion=1, caseId='E1',
                assertions=[{'id': k, 'pass': v, 'evidenceRefs': ['plan-replay:' + k]} for k, v in checks.items()],
                hardGates=[{'id': k, 'violated': v, 'evidenceRefs': ['plan-replay:' + k]} for k, v in gates.items()],
                components=[{'id': 'strictTask', 'earnedPoints': 100 if all(checks.values()) and not any(gates.values()) else 0,
                             'maxPoints': 100, 'source': 'DETERMINISTIC', 'evidenceRefs': ['component:strictTask']}],
                verifierSha256=contract['verifierSha256'], toolchainSha256=contract['toolchainSha256'])


def main():
    require(len(sys.argv) == 3, 'arguments')
    private = pathlib.Path(__file__).parent
    oracle_file = private / 'oracles/E1.json'
    oracle = load(oracle_file, 131072)
    evidence = load(pathlib.Path(sys.argv[2]), 16 * 1048576)
    contract = load(private / 'scoring-contracts/E1.json', 32768)
    print(compact(verify(oracle, evidence, pathlib.Path(sys.argv[1]), contract, digest(oracle_file.read_bytes()))))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('E1 verifier input or infrastructure invalid; no score', file=sys.stderr)
        raise SystemExit(2)
