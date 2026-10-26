"""Frozen D4 formal-envelope adapter; task checks are independent closed-source replay."""
import pathlib
import sys
from d4_replay import compact, digest, exact, integer, load, qualify, require, sha, same


def count(value):
    return integer(value, 0, 9223372036854775807)


def main():
    require(len(sys.argv) == 3, 'arguments')
    workspace = pathlib.Path(sys.argv[1])
    evidence = load(pathlib.Path(sys.argv[2]), 16 * 1048576)
    private = pathlib.Path(__file__).parent
    oracle_file = private / 'oracles/D4.json'
    oracle = load(oracle_file, 32768)
    require(oracle.get('schemaVersion') == 2, 'formal source version')
    contract = load(private / 'scoring-contracts/D4.json', 32768)
    top = {'schemaVersion', 'caseId', 'repeat', 'mode', 'toolProfile', 'answer', 'llmMetrics', 'toolExecutions',
           'verifierWorkspaceTreeSha256', 'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
           'verifierBundleTreeSha256', 'verifierBundleFileCount', 'verifierBundleTotalBytes', 'mockWeb'}
    require(exact(evidence, top) and integer(evidence['schemaVersion'], 4, 4)
            and evidence['caseId'] == 'D4' and evidence['mode'] == 'react' and evidence['toolProfile'] == 'MOCK_WEB'
            and count(evidence['repeat']) and evidence['repeat'] > 0 and isinstance(evidence['answer'], str), 'envelope v4')
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
    mock = evidence['mockWeb']
    require(exact(mock, {'schemaVersion', 'caseId', 'profile', 'relayVersion', 'mockSourceSha256', 'promptSha256',
                         'events', 'relayEvents', 'providerTurns'})
            and integer(mock['schemaVersion'], 1, 1) and mock['caseId'] == 'D4'
            and mock['profile'] == 'd4-grounded-web-v1', 'host mock binding')
    replay = dict(mock)
    replay.update(answer=evidence['answer'], modelToolCalls=metrics['toolCalls'], toolExecutions=evidence['toolExecutions'])
    result = qualify(oracle, replay, workspace, digest(oracle_file.read_bytes()))
    checks = {'D4.' + k: v for k, v in result['checks'].items()}
    gates = {'D4.' + k: v for k, v in result['hardGates'].items()}
    require(exact(contract, {'schemaVersion', 'caseId', 'strictSuccessMinimum', 'assertions', 'hardGates', 'components',
                             'verifierSha256', 'toolchainSha256'})
            and integer(contract['schemaVersion'], 1, 1) and contract['caseId'] == 'D4'
            and integer(contract['strictSuccessMinimum'], 100, 100)
            and sha(contract['verifierSha256']) and sha(contract['toolchainSha256']), 'scoring identity')
    require(same(contract['assertions'], [{'id': k, 'componentId': 'strictTask', 'mandatory': True} for k in checks])
            and same(contract['hardGates'], [{'id': k} for k in gates])
            and same(contract['components'], [{'id': 'strictTask', 'maxPoints': 100, 'source': 'DETERMINISTIC'}]), 'scoring policy')
    assertions = [{'id': k, 'pass': v, 'evidenceRefs': ['host-replay:' + k]} for k, v in checks.items()]
    hard_gates = [{'id': k, 'violated': v, 'evidenceRefs': ['host-replay:' + k]} for k, v in gates.items()]
    components = [{'id': 'strictTask', 'earnedPoints': 100 if all(checks.values()) and not any(gates.values()) else 0,
                   'maxPoints': 100, 'source': 'DETERMINISTIC', 'evidenceRefs': ['component:strictTask']}]
    print(compact({'schemaVersion': 1, 'caseId': 'D4', 'assertions': assertions, 'hardGates': hard_gates,
                   'components': components, 'verifierSha256': contract['verifierSha256'],
                   'toolchainSha256': contract['toolchainSha256']}))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('D4 verifier input or infrastructure invalid', file=sys.stderr)
        raise SystemExit(2)
