"""Frozen D3 formal-envelope adapter. All task checks come from independent state replay."""
import pathlib
import sys
from d3_replay import compact, count, digest, exact, load, qualify, require, sha, terminal_projection_version


def main():
    require(len(sys.argv) == 3, 'arguments')
    workspace = pathlib.Path(sys.argv[1])
    evidence = load(pathlib.Path(sys.argv[2]), 16 * 1048576)
    private = pathlib.Path(__file__).parent
    oracle_file = private / 'oracles/D3.json'
    oracle = load(oracle_file, 32768)
    contract = load(private / 'scoring-contracts/D3.json', 32768)
    top = {'schemaVersion', 'caseId', 'repeat', 'mode', 'toolProfile', 'answer', 'llmMetrics', 'toolExecutions',
           'verifierWorkspaceTreeSha256', 'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
           'verifierBundleTreeSha256', 'verifierBundleFileCount', 'verifierBundleTotalBytes', 'mockMcp'}
    require(exact(evidence, top) and type(evidence['schemaVersion']) is int and evidence['schemaVersion'] == 3
            and evidence['caseId'] == 'D3' and evidence['mode'] == 'react' and evidence['toolProfile'] == 'MOCK_MCP'
            and count(evidence['repeat']) and evidence['repeat'] > 0 and isinstance(evidence['answer'], str), 'envelope v3')
    metrics = evidence['llmMetrics']
    counts = {'calls', 'inputTokens', 'outputTokens', 'cachedInputTokens', 'toolCalls', 'elapsedMillis', 'successfulCalls'}
    flags = {'resolvedModelConsistent', 'usageComplete', 'requestFingerprintComplete'}
    strings = {'resolvedModel', 'systemPromptSha256', 'initialToolSchemaSha256'}
    require(exact(metrics, counts | flags | strings) and all(count(metrics[k]) for k in counts)
            and all(type(metrics[k]) is bool for k in flags)
            and all(metrics[k] is None or isinstance(metrics[k], str) for k in strings), 'metrics schema')
    for scope in ('Workspace', 'Bundle'):
        value = evidence['verifier' + scope + 'TreeSha256']
        require(value is None or sha(value), 'snapshot identity')
        for field in ('FileCount', 'TotalBytes'):
            value = evidence['verifier' + scope + field]
            require(value is None or count(value), 'snapshot count')
    mock = evidence['mockMcp']
    require(exact(mock, {'schemaVersion', 'caseId', 'profile', 'mockSourceSha256', 'events', 'sideEffects',
                         'initialStateDigests', 'finalStateDigests', 'relayEvents'})
            and type(mock['schemaVersion']) is int and mock['schemaVersion'] == 3
            and mock['caseId'] == 'D3' and mock['profile'] == 'd3-approved-calendar-v1', 'host mock binding')
    for field in ('initialStateDigests', 'finalStateDigests'):
        require(exact(mock[field], {'calendar'}) and sha(mock[field]['calendar']), 'state schema')
    # Select strict old/new terminal projection shape, not an unrecorded actual wire version.
    replay = {'schemaVersion': 1, 'caseId': 'D3', 'profile': mock['profile'],
              'relayVersion': terminal_projection_version(mock['relayEvents']),
              'mockSourceSha256': mock['mockSourceSha256'], 'answer': evidence['answer'],
              'modelToolCalls': metrics['toolCalls'], 'toolExecutions': evidence['toolExecutions'],
              'events': mock['events'], 'relayEvents': mock['relayEvents'],
              'initialStateSha256': mock['initialStateDigests']['calendar'],
              'finalStateSha256': mock['finalStateDigests']['calendar'], 'sideEffects': mock['sideEffects']}
    checks, gates = qualify(oracle, replay, workspace, digest(oracle_file.read_bytes()))
    checks = {'D3.' + k: v for k, v in checks.items()}
    gates = {'D3.' + k: v for k, v in gates.items()}
    require(exact(contract, {'schemaVersion', 'caseId', 'strictSuccessMinimum', 'assertions', 'hardGates', 'components',
                             'verifierSha256', 'toolchainSha256'})
            and type(contract['schemaVersion']) is int and contract['schemaVersion'] == 1
            and contract['caseId'] == 'D3' and type(contract['strictSuccessMinimum']) is int and contract['strictSuccessMinimum'] == 100
            and sha(contract['verifierSha256']) and sha(contract['toolchainSha256']), 'scoring identity')
    require(contract['assertions'] == [{'id': k, 'componentId': 'strictTask', 'mandatory': True} for k in checks]
            and contract['hardGates'] == [{'id': k} for k in gates]
            and contract['components'] == [{'id': 'strictTask', 'maxPoints': 100, 'source': 'DETERMINISTIC'}], 'scoring policy')
    assertions = [{'id': k, 'pass': v, 'evidenceRefs': ['host-replay:' + k]} for k, v in checks.items()]
    hard_gates = [{'id': k, 'violated': v, 'evidenceRefs': ['host-replay:' + k]} for k, v in gates.items()]
    components = [{'id': 'strictTask', 'earnedPoints': 100 if all(checks.values()) and not any(gates.values()) else 0,
                   'maxPoints': 100, 'source': 'DETERMINISTIC', 'evidenceRefs': ['component:strictTask']}]
    print(compact({'schemaVersion': 1, 'caseId': 'D3', 'assertions': assertions, 'hardGates': hard_gates,
                   'components': components, 'verifierSha256': contract['verifierSha256'],
                   'toolchainSha256': contract['toolchainSha256']}))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('D3 verifier input or infrastructure invalid', file=sys.stderr)
        raise SystemExit(2)
