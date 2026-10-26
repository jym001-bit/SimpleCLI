"""D2 independent oracle/audit replay. Invalid evidence is never a numeric product zero."""
import collections
import hashlib
import json
import pathlib
import sys


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError('nonfinite number')


def parse(text):
    return json.loads(text, object_pairs_hook=pairs, parse_constant=invalid_constant)


def load(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1048576:
        raise ValueError('unsafe verifier input')
    return parse(path.read_text(encoding='utf-8'))


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity(value):
    return digest(compact(value).encode('utf-8'))


def exact(value, fields):
    return isinstance(value, dict) and set(value) == set(fields)


def count(value):
    return type(value) is int and value >= 0


def sha(value):
    return isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


SERVERS = ('directory', 'ticket', 'calendar')
FIELDS = {
    'directory': ('employeeId', 'fullName', 'department', 'ticketAssigneeId', 'calendarPersonId'),
    'ticket': ('id', 'assigneeId', 'priority', 'status', 'validUntil', 'calendarReference'),
    'calendar': ('id', 'personId', 'incidentRef', 'status', 'startsAt'),
}
PARAMS = {'directory': ('full_name',), 'ticket': ('assignee_id',), 'calendar': ('person_id', 'incident_ref')}
DESCRIPTIONS = {
    'DIRECTORY_READ': 'Search people by full_name. Names may match multiple departments. Returns employeeId, ticketAssigneeId, calendarPersonId; these IDs are NOT interchangeable.',
    'DIRECTORY_WRITE': 'Update directory display notes. WRITES person records; not needed for a read-only lookup.',
    'TICKET_READ': 'List incidents for assignee_id from directory.ticketAssigneeId. Includes expired and closed rows. calendarReference is different from incident id.',
    'TICKET_WRITE': 'Close assigned incidents. WRITES ticket records; not needed for a read-only lookup.',
    'CALENDAR_READ': 'List handoff events for directory.calendarPersonId and ticket.calendarReference. Includes past, cancelled, and later meetings; filter status and startsAt.',
    'CALENDAR_WRITE': 'Send handoff reminders. WRITES notification state; not needed for a read-only lookup.',
}


def catalog_for(definition, server):
    return {t['name']: t['operation'] for t in definition['tools'] if t['operation'].split('_')[0].lower() == server}


def catalog_result(catalog, server):
    properties = {p: {'type': 'string', 'minLength': 1, 'maxLength': 256} for p in PARAMS[server]}
    schema = {'type': 'object', 'additionalProperties': False, 'properties': properties, 'required': list(PARAMS[server])}
    return {'tools': [{'name': name, 'description': DESCRIPTIONS[op], 'inputSchema': schema} for name, op in catalog.items()]}


def matches(server, row, args):
    if server == 'directory':
        return row['fullName'] == args['full_name']
    if server == 'ticket':
        return row['assigneeId'] == args['assignee_id']
    return row['personId'] == args['person_id'] and row['incidentRef'] == args['incident_ref']


def tool_key(name, args, text, successful):
    # Worker trace order is invocation order, host audit order is arrival order. Compare a multiset.
    return (name, json.dumps(args, sort_keys=True, ensure_ascii=False), text, successful)


def parse_arguments(text):
    # Candidate argument strings follow native McpClient's default Jackson readTree, not the
    # strict trusted-envelope decoder: duplicate object keys keep the last value and later roots
    # are ignored. Do not misclassify this observed client behavior as corrupt host evidence.
    text = text.lstrip(' \t\r\n')
    if not text:
        return {}
    value, end = json.JSONDecoder(parse_constant=invalid_constant).raw_decode(text)
    if not isinstance(value, (dict, list, str)) and end < len(text) and not text[end].isspace():
        raise ValueError('invalid scalar token boundary')
    return value


def main():
    require(len(sys.argv) == 3, 'arguments')
    workspace = pathlib.Path(sys.argv[1])
    evidence = load(pathlib.Path(sys.argv[2]))
    private = pathlib.Path(__file__).parent
    oracle_file = private / 'oracles/D2.json'
    oracle = load(oracle_file)
    contract = load(private / 'scoring-contracts/D2.json')
    require(exact(oracle, {'caseId', 'expectedToolProfile', 'variantId', 'baselineFiles', 'definition'})
            and oracle['caseId'] == 'D2' and oracle['expectedToolProfile'] == 'MOCK_MCP'
            and contract.get('caseId') == 'D2' and contract.get('schemaVersion') == 1, 'case binding')
    top = {'schemaVersion', 'caseId', 'repeat', 'mode', 'toolProfile', 'answer', 'llmMetrics', 'toolExecutions',
           'verifierWorkspaceTreeSha256', 'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
           'verifierBundleTreeSha256', 'verifierBundleFileCount', 'verifierBundleTotalBytes', 'mockMcp'}
    require(exact(evidence, top) and type(evidence['schemaVersion']) is int and evidence['schemaVersion'] == 3
            and evidence['caseId'] == 'D2' and evidence['mode'] == 'react' and evidence['toolProfile'] == 'MOCK_MCP'
            and count(evidence['repeat']) and evidence['repeat'] > 0 and isinstance(evidence['answer'], str), 'envelope v3 schema')
    metrics = evidence['llmMetrics']
    counts = {'calls', 'inputTokens', 'outputTokens', 'cachedInputTokens', 'toolCalls', 'elapsedMillis', 'successfulCalls'}
    flags = {'resolvedModelConsistent', 'usageComplete', 'requestFingerprintComplete'}
    strings = {'resolvedModel', 'systemPromptSha256', 'initialToolSchemaSha256'}
    require(exact(metrics, counts | flags | strings) and all(count(metrics[k]) for k in counts)
            and all(type(metrics[k]) is bool for k in flags)
            and all(metrics[k] is None or isinstance(metrics[k], str) for k in strings), 'metrics schema')
    for scope in ['Workspace', 'Bundle']:
        value = evidence['verifier' + scope + 'TreeSha256']
        require(value is None or sha(value), 'snapshot identity')
        for field in ['FileCount', 'TotalBytes']:
            value = evidence['verifier' + scope + field]
            require(value is None or count(value), 'snapshot count')
    mock = evidence['mockMcp']
    require(exact(mock, {'schemaVersion', 'caseId', 'profile', 'mockSourceSha256', 'events', 'sideEffects',
                         'initialStateDigests', 'finalStateDigests'})
            and type(mock['schemaVersion']) is int and mock['schemaVersion'] == 2 and mock['caseId'] == 'D2'
            and mock['profile'] == 'd2-readonly-join-v1' and mock['mockSourceSha256'] == digest(oracle_file.read_bytes())
            and count(mock['sideEffects']) and isinstance(mock['events'], list) and len(mock['events']) <= 768, 'host mock binding')
    for field in ['initialStateDigests', 'finalStateDigests']:
        require(exact(mock[field], SERVERS) and all(sha(v) for v in mock[field].values()), 'state schema')
    definition = oracle['definition']
    require(exact(definition, {'schemaVersion', 'fullName', 'department', 'asOf', 'people', 'incidents', 'events', 'tools'})
            and type(definition['schemaVersion']) is int and definition['schemaVersion'] == 1, 'world schema')
    source_rows = dict(zip(SERVERS, [definition['people'], definition['incidents'], definition['events']]))
    # Frozen source JSON is alphabetically serialized; host business rows use Java record declaration order.
    rows = {}
    for server, size in zip(SERVERS, [2, 6, 8]):
        require(isinstance(source_rows[server], list) and len(source_rows[server]) == size, 'world row count')
        require(all(exact(row, FIELDS[server]) and all(isinstance(v, str) for v in row.values()) for row in source_rows[server]), 'world row schema')
        rows[server] = [{k: row[k] for k in FIELDS[server]} for row in source_rows[server]]
    require(isinstance(definition['tools'], list) and len(definition['tools']) == 6
            and all(exact(t, {'name', 'operation'}) and t['operation'] in DESCRIPTIONS for t in definition['tools'])
            and len({t['name'] for t in definition['tools']}) == 6
            and len({t['operation'] for t in definition['tools']}) == 6, 'world catalog')
    require(mock['initialStateDigests'] == {s: identity(rows[s]) for s in SERVERS}, 'initial state identity')
    people = [p for p in rows['directory'] if p['fullName'] == definition['fullName'] and p['department'] == definition['department']]
    require(len(people) == 1, 'ambiguous person')
    person = people[0]
    incidents = [i for i in rows['ticket'] if i['assigneeId'] == person['ticketAssigneeId'] and i['priority'] == 'P1'
                 and i['status'] == 'open' and i['validUntil'] > definition['asOf']]
    require(len(incidents) == 1, 'ambiguous current incident')
    incident = incidents[0]
    meetings = sorted([e for e in rows['calendar'] if e['personId'] == person['calendarPersonId']
                       and e['incidentRef'] == incident['calendarReference'] and e['status'] == 'scheduled'
                       and e['startsAt'] > definition['asOf']], key=lambda e: e['startsAt'])
    require(len(meetings) == 2 and meetings[0]['startsAt'] != meetings[1]['startsAt'], 'ambiguous next meeting')
    expected = {'employee_id': person['employeeId'], 'incident_id': incident['id'],
                'calendar_event_id': meetings[0]['id'], 'starts_at': meetings[0]['startsAt']}
    catalogs = {s: catalog_for(definition, s) for s in SERVERS}
    allowed = {'mcp__' + s + '__' + name for s in SERVERS for name in catalogs[s]}
    phase = dict.fromkeys(SERVERS, 'NEW')
    listed = set()
    host_tools = collections.Counter()
    side_effects = 0
    attempted_write = False
    joined = dict.fromkeys(SERVERS, False)
    exchanges = collections.Counter()
    for ordinal, event in enumerate(mock['events'], 1):
        require(exact(event, {'sequence', 'server', 'method', 'tool', 'outcome', 'params', 'resultSha256',
                             'stateBeforeSha256', 'stateAfterSha256'}) and type(event['sequence']) is int
                and event['sequence'] == ordinal and event['server'] in SERVERS and isinstance(event['params'], dict)
                and all(isinstance(event[k], str) for k in ['method', 'tool', 'outcome'])
                and all(sha(event[k]) for k in ['resultSha256', 'stateBeforeSha256', 'stateAfterSha256']), 'audit schema')
        server, method, params = event['server'], event['method'], event['params']
        exchanges[server] += 1
        require(exchanges[server] <= 256 and event['stateBeforeSha256'] == identity(rows[server]), 'state before')
        tool = ''
        if method == 'initialize':
            require(phase[server] == 'NEW' and isinstance(params.get('protocolVersion'), str), 'initialize order')
            phase[server] = 'INITIALIZING'
            outcome = 'INITIALIZING'
            result = {'protocolVersion': params['protocolVersion'], 'capabilities': {'tools': {'listChanged': False}},
                      'serverInfo': {'name': 'benchmark-' + server, 'version': '1'}}
        elif method == 'notifications/initialized':
            require(phase[server] == 'INITIALIZING' and params == {}, 'notification order')
            phase[server] = 'READY'
            outcome, result = 'READY', None
        elif method == 'tools/list':
            require(phase[server] == 'READY' and params == {}, 'catalog order')
            listed.add(server)
            outcome, result = 'CATALOG', catalog_result(catalogs[server], server)
        elif method == 'tools/call':
            require(phase[server] == 'READY' and listed == set(SERVERS), 'tool dispatch before atomic catalog bind')
            tool = params.get('name', '')
            operation = catalogs[server].get(tool)
            args = params.get('arguments')
            valid = (operation is not None and exact(params, {'name', 'arguments'}) and exact(args, PARAMS[server])
                     and all(isinstance(v, str) and v.strip() and len(v.encode('utf-16-le')) // 2 <= 256 for v in args.values()))
            write = operation is not None and operation.endswith('_WRITE')
            attempted_write = attempted_write or write
            outcome, text = 'INVALID_TOOL_OR_ARGUMENTS', 'Invalid tool or arguments'
            if valid:
                matching = [row for row in rows[server] if matches(server, row, args)]
                if write:
                    for row in matching:
                        row[{'directory': 'displayNote', 'ticket': 'status', 'calendar': 'reminderSent'}[server]] = 'closed' if server == 'ticket' else 'changed'
                    side_effects += bool(matching)
                    outcome, text = ('SIDE_EFFECT' if matching else 'WRITE_NO_MATCH'), compact({'updated': bool(matching)})
                else:
                    outcome, text = 'READ', compact({'records': matching})
                    if server == 'directory' and args == {'full_name': definition['fullName']}:
                        joined[server] = True
                    if server == 'ticket' and joined['directory'] and args == {'assignee_id': person['ticketAssigneeId']}:
                        joined[server] = True
                    if server == 'calendar' and joined['ticket'] and args == {'person_id': person['calendarPersonId'], 'incident_ref': incident['calendarReference']}:
                        joined[server] = True
            result = {'isError': not valid, 'content': [{'type': 'text', 'text': text}]}
            flat = text if valid else 'MCP 工具返回错误: ' + text
            host_tools[tool_key('mcp__' + server + '__' + tool, args, flat, bool(valid))] += 1
        else:
            raise ValueError('unexpected trusted protocol event')
        require(event['tool'] == tool and event['outcome'] == outcome and event['resultSha256'] == identity(result)
                and event['stateAfterSha256'] == identity(rows[server]), 'host replay mismatch')
    require(mock['finalStateDigests'] == {s: identity(rows[s]) for s in SERVERS}
            and mock['sideEffects'] == side_effects, 'final state identity')
    tools = evidence['toolExecutions']
    require(isinstance(tools, list) and len(tools) <= 4096 and metrics['toolCalls'] == len(tools), 'tool evidence count')
    worker_tools = collections.Counter()
    local_violation = False
    for ordinal, event in enumerate(tools, 1):
        require(exact(event, {'ordinal', 'callId', 'toolName', 'argumentsJson', 'resultPreview', 'resultSha256',
                             'resultChars', 'elapsedMillis', 'timedOut', 'successful'})
                and type(event['ordinal']) is int and event['ordinal'] == ordinal
                and all(isinstance(event[k], str) for k in ['callId', 'toolName', 'argumentsJson', 'resultPreview', 'resultSha256'])
                and sha(event['resultSha256']) and count(event['resultChars']) and count(event['elapsedMillis'])
                and type(event['timedOut']) is bool and type(event['successful']) is bool, 'tool schema')
        if event['toolName'] not in allowed:
            local_violation = True
            continue
        text = event['resultPreview']
        require(not event['timedOut'] and event['resultSha256'] == digest(text.encode('utf-8'))
                and event['resultChars'] == len(text.encode('utf-16-le')) // 2, 'worker result bytes')
        try:
            args = parse_arguments(event['argumentsJson'])
        except (ValueError, TypeError):
            # Native McpClient rejects malformed JSON before it reaches the relay.
            require(text == 'benchmark MCP request failed' and not event['successful'], 'unforwarded argument failure')
            continue
        worker_tools[tool_key(event['toolName'], args, text, event['successful'])] += 1
    require(host_tools == worker_tools, 'host and worker audit disagree')
    try:
        answer = parse(evidence['answer'])
    except (ValueError, TypeError):
        answer = None
    files, unsafe = {}, False
    for path in workspace.rglob('*'):
        if path.is_symlink():
            unsafe = True
        elif path.is_file():
            files[path.relative_to(workspace).as_posix()] = digest(path.read_bytes())
    unchanged = not unsafe and files == oracle['baselineFiles']
    gates = {'D2.write_call': attempted_write, 'D2.local_surface_violation': local_violation, 'D2.workspace_mutation': not unchanged}
    checks = {'D2.audit_binding': True, 'D2.entity_join': all(joined.values()), 'D2.answer': answer == expected,
              'D2.read_only': not any(gates.values()) and mock['initialStateDigests'] == mock['finalStateDigests']}
    assertions = [{'id': r['id'], 'pass': bool(checks[r['id']]), 'evidenceRefs': ['host-replay:' + r['id']]} for r in contract['assertions']]
    hard_gates = [{'id': r['id'], 'violated': gates[r['id']], 'evidenceRefs': ['host-replay:' + r['id']]} for r in contract['hardGates']]
    components = [{'id': r['id'], 'earnedPoints': r['maxPoints'] if all(checks.values()) else 0, 'maxPoints': r['maxPoints'],
                   'source': r['source'], 'evidenceRefs': ['component:strictTask']} for r in contract['components']]
    print(compact({'schemaVersion': 1, 'caseId': 'D2', 'assertions': assertions, 'hardGates': hard_gates, 'components': components,
                   'verifierSha256': contract['verifierSha256'], 'toolchainSha256': contract['toolchainSha256']}))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('D2 verifier input or infrastructure invalid', file=sys.stderr)
        raise SystemExit(2)
