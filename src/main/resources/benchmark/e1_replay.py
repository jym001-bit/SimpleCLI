"""Independent E1 Plan/data-join diagnostic. No Java, network or model calls.

Only schema-2 host audits are replayable; older evidence is never backfilled.
This prototype is NOT a formal scoring adapter. Invalid evidence exits 2 with
no verdict. Consistent but unsuccessful Candidate behavior is a valid failure.
"""
import collections
import hashlib
import json
import pathlib
import posixpath
import re
import sys
import uuid


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def parse(text):
    def nonfinite(_):
        raise ValueError('nonfinite JSON')
    return json.loads(text, object_pairs_hook=pairs, parse_constant=nonfinite)


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def same(a, b):
    return json.dumps(a, sort_keys=True, ensure_ascii=False) == json.dumps(b, sort_keys=True, ensure_ascii=False)


def digest(value):
    return hashlib.sha256(value.encode('utf-8') if isinstance(value, str) else value).hexdigest()


def exact(value, fields):
    return isinstance(value, dict) and set(value) == set(fields)


def integer(value, low=0, high=2**63-1):
    return type(value) is int and low <= value <= high


def string(value, maximum=262144):
    return isinstance(value, str) and len(value.encode('utf-8')) <= maximum


def java_blank(value):
    # String.isBlank uses Character.isWhitespace, not Python's broader Unicode whitespace set.
    whitespace = '\u0009\u000a\u000b\u000c\u000d\u001c\u001d\u001e\u001f\u0020\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000'
    return value is None or all(char in whitespace for char in value)


def sha(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def fingerprint(text):
    return dict(present=text is not None, utf8Bytes=0 if text is None else len(text.encode('utf-8')),
                sha256=None if text is None else digest(text))


def fp(value):
    require(exact(value, ('present', 'utf8Bytes', 'sha256')) and type(value['present']) is bool
            and integer(value['utf8Bytes'], 0, 16_777_216)
            and (sha(value['sha256']) if value['present'] else value['sha256'] is None and value['utf8Bytes'] == 0), 'fingerprint shape')


def array(value, maximum):
    require(isinstance(value, list) and len(value) <= maximum, 'bounded array')
    return value


def load(path, maximum):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= maximum, 'input file')
    return parse(path.read_text(encoding='utf-8'))


def prompt(oracle=None):
    task = ('Use Plan mode for two independent CSV analyses followed by a dependent merge. '
            'Create exactly three tasks with descriptions LEFT, RIGHT, and MERGE (original task IDs may vary). '
            'LEFT and RIGHT must have no dependencies and run in overlapping task lifecycles; MERGE must depend on both. '
            'LEFT reads only left.csv; RIGHT reads only right.csv. Each branch returns only a JSON object with '
            'branch (left or right), rows (integer row count excluding header), ids (all id strings in source order), '
            'and sum_cents (integer sum of amount_cents). Preserve every row, including negative amounts. '
            'MERGE must consume both complete branch results, not reread the CSV files, and write report.json with '
            'exactly left and right (the complete branch objects), and combined_cents (the sum of both sum_cents). '
            'Do not modify the CSV inputs or create other files. Use only read_file and write_file; do not use commands or network. '
            'The merge task may read report.json for verification. Do not wrap branch JSON in Markdown fences.')
    if oracle is not None and oracle['schemaVersion'] == 2:
        return '# E1 Plan DAG 并行与依赖汇总\n\n' + task + '\n\nVariant: ' + oracle['variantId'] + '\n'
    return task


def source(oracle):
    fields = {'schemaVersion', 'caseId', 'profile', 'variantId', 'files'}
    require(isinstance(oracle, dict) and integer(oracle.get('schemaVersion'), 1, 2), 'source version')
    if oracle['schemaVersion'] == 2:
        fields.add('expectedToolProfile')
        require(oracle.get('expectedToolProfile') == 'FILE_ONLY', 'source tool profile')
    require(exact(oracle, fields) and oracle['caseId'] == 'E1'
            and oracle['profile'] == 'e1-plan-join-v1' and isinstance(oracle['variantId'], str)
            and re.fullmatch('[a-f0-9]{24}', oracle['variantId'])
            and exact(oracle['files'], ('left.csv', 'right.csv')), 'E1 source schema')
    branches = {}
    for side in ('left', 'right'):
        text = oracle['files'][side + '.csv']
        require(string(text, 32768) and text.startswith('id,amount_cents\n') and text.endswith('\n'), 'CSV framing')
        rows = text.split('\n')[1:-1]
        require(2 <= len(rows) <= 40, 'CSV row count')
        ids, amounts = [], []
        for row in rows:
            m = re.fullmatch(r'([A-Za-z0-9_\-\u4e00-\u9fff]{1,64}),(-?(?:0|[1-9][0-9]{0,6}))', row)
            require(m is not None, 'CSV source row')
            ids.append(m[1]); amounts.append(int(m[2]))
        require(len(set(ids)) == len(ids), 'CSV source identity')
        branches[side] = dict(branch=side, rows=len(rows), ids=ids, sum_cents=sum(amounts))
    return branches


def tool_path(args, workspace):
    if not isinstance(args, dict) or not isinstance(args.get('path'), str):
        return None
    path = pathlib.PurePosixPath(posixpath.normpath(args['path']))
    if path.is_absolute():
        try:
            path = path.relative_to(workspace.absolute())
        except ValueError:
            return None
    return path.as_posix()


def read_result(name, text, args):
    """Independently reproduce native full/ranged read_file; union actual observed rows."""
    lines = text.splitlines()
    if 'offset' not in args and 'limit' not in args:
        return '文件内容:\n' + text, set(range(len(lines)))
    def parameter(key, default):
        value = args.get(key)
        value = value if isinstance(value, str) else compact(value)
        value = value.strip(''.join(chr(i) for i in range(33)))
        if not re.fullmatch(r'[+-]?\d+', value):
            return default
        n = int(value)
        return n if -2**31 <= n <= 2**31-1 else default
    offset, limit = max(1, parameter('offset', 1)), max(1, min(parameter('limit', 200), 2000))
    if offset > len(lines):
        return '文件内容: %s 共 %d 行，offset 超出范围' % (name, len(lines)), set()
    end = min(offset - 1 + limit, len(lines))
    result = '文件内容: %s (lines %d-%d of %d)\n' % (name, offset, end, len(lines))
    result += ''.join('%5d | %s\n' % (i+1, lines[i]) for i in range(offset-1, end))
    if end < len(lines):
        result += '...(已截断，可用 offset=%d 继续读取)' % (end+1)
    return result.strip(''.join(chr(i) for i in range(33))), set(range(offset-1, end))


def canonical(value):
    if isinstance(value, dict):
        return {key: canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [canonical(v) for v in value]
    return value


def jackson_canonical(value):
    """TracingLlmClient's sorted UTF8JsonGenerator form, including surrogate escapes."""
    if isinstance(value, str):
        result = []
        escapes = {'"': '\\"', '\\': '\\\\', '\b': '\\b', '\f': '\\f', '\n': '\\n', '\r': '\\r', '\t': '\\t'}
        for char in value:
            n = ord(char)
            if char in escapes:
                result.append(escapes[char])
            elif n < 32:
                result.append('\\u%04X' % n)
            elif n > 0xFFFF:
                n -= 0x10000
                result.append('\\u%04X\\u%04X' % (0xD800 + (n >> 10), 0xDC00 + (n & 1023)))
            else:
                result.append(char)
        return '"' + ''.join(result) + '"'
    if isinstance(value, dict):
        return '{' + ','.join(jackson_canonical(k) + ':' + jackson_canonical(value[k]) for k in sorted(value)) + '}'
    if isinstance(value, list):
        return '[' + ','.join(jackson_canonical(v) for v in value) + ']'
    return compact(value)


def call_shape(call):
    require(exact(call, ('id', 'function')) and string(call['id'], 256) and call['id']
            and exact(call['function'], ('name', 'arguments')) and string(call['function']['name'], 256)
            and string(call['function']['arguments']), 'tool call shape')


def message_shape(message):
    require(exact(message, ('role', 'content', 'reasoningContent', 'toolCalls', 'toolCallId', 'contentParts'))
            and message['role'] in ('system', 'user', 'assistant', 'tool')
            and (message['content'] is None or string(message['content'], 1_048_576))
            and (message['reasoningContent'] is None or string(message['reasoningContent'], 1_048_576))
            and (message['toolCallId'] is None or string(message['toolCallId'], 256))
            and message['contentParts'] == [], 'text message shape')
    for call in array(message['toolCalls'], 128):
        call_shape(call)


def normalize_plan(content):
    try:
        value = parse(re.sub(r'```\s*', '', re.sub(r'```json\s*', '', content)).strip())
        tasks = value['tasks']
        if not isinstance(tasks, list) or not 1 <= len(tasks) <= 128:
            return None
        ids = [t.get('id') for t in tasks]
        if not all(isinstance(i, str) and not java_blank(i) for i in ids) or len(set(ids)) != len(ids):
            return None
        mapping = {old: 'task_' + str(i + 1) for i, old in enumerate(ids)}
        normalized = []
        for task in tasks:
            deps = task.get('dependencies', [])
            if not isinstance(deps, list) or not all(isinstance(d, str) and d in mapping for d in deps):
                return None
            description = task.get('description', '')
            if not isinstance(description, str):
                return None
            kind = str(task.get('type', '')).upper()
            if kind not in ('FILE_READ', 'FILE_WRITE', 'COMMAND', 'ANALYSIS', 'VERIFICATION'):
                kind = 'ANALYSIS'
            normalized.append(dict(taskId=mapping[task['id']], type=kind,
                                   description=fingerprint(description),
                                   dependencies=list(dict.fromkeys(mapping[d] for d in deps))))
        return normalized, {mapping[t['id']]: t.get('description', '') for t in tasks}
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


def report(checks, hard):
    return dict(schemaVersion=1, caseId='E1', evaluationValid=True,
                diagnosticSatisfied=all(checks.values()) and not any(hard.values()), checks=checks, hardGates=hard,
                publicationEligible=False, formalScore=None,
                boundary='PROTOTYPE_HOST_REPLAY_NOT_FORMAL_SCORE_OR_CPU_PARALLELISM_PROOF')


def tool_record(tool, ordinal):
    require(exact(tool, ('ordinal', 'callId', 'toolName', 'argumentsJson', 'resultPreview', 'resultSha256',
                        'resultChars', 'elapsedMillis', 'timedOut', 'successful'))
            and integer(tool['ordinal'], ordinal, ordinal) and string(tool['callId'], 256) and string(tool['toolName'], 256)
            and string(tool['argumentsJson']) and string(tool['resultPreview']) and sha(tool['resultSha256'])
            and integer(tool['resultChars']) and integer(tool['elapsedMillis'])
            and type(tool['timedOut']) is bool and type(tool['successful']) is bool
            and not (tool['timedOut'] and tool['successful']), 'global tool schema')
    # This display field may be redacted as well as truncated. It is not the text
    # covered by resultSha256/resultChars and must never substitute for raw output.
    return (tool['callId'], tool['toolName'], tool['argumentsJson'], tool['resultSha256'],
            tool['resultChars'], tool['successful'], tool['timedOut'])


def terminal_tool_record(call, result, tools):
    """A returned batch need not reach another provider request before a local exception.

    Correlate it to the complete global execution ledger, never invent a tool message or
    claim the model observed this result. A truncated preview is not the full result.
    """
    matches = [(tool, record) for tool, record in tools
               if record[:4] == (call['id'], call['function']['name'], call['function']['arguments'], result['result']['sha256'])
               and record[5:] == (result['successful'], result['timedOut'])]
    require(matches and len(set(record for _, record in matches)) == 1, 'terminal batch/global correlation')
    _, record = matches[0]
    require(result['result']['present'] and record[4] <= result['result']['utf8Bytes'] <= 3 * record[4],
            'terminal result missing/impossible text length')
    # Exact UTF-8 byte length cannot be recomputed without the raw text. The SHA
    # and UTF-16 count are tied to the global record; no semantic credit uses the
    # preview or claims the unreturned result reached a model.
    return record


def qualify(oracle, evidence, workspace, source_hash):
    branches = source(oracle)
    goal = prompt(oracle)
    require(exact(evidence, ('schemaVersion', 'caseId', 'profile', 'relayVersion', 'sourceSha256',
                            'planAudit', 'scopedRequestFingerprints', 'toolExecutions', 'modelToolCalls'))
            and integer(evidence['schemaVersion'], 1, 1) and evidence['caseId'] == 'E1'
            # v10 leaves scoped Plan requests, tool evidence and Plan audit unchanged.
            and evidence['profile'] == oracle['profile'] and integer(evidence['relayVersion'], 9, 12)
            and evidence['sourceSha256'] == source_hash, 'evidence envelope/source binding')
    audit, scoped = evidence['planAudit'], evidence['scopedRequestFingerprints']
    require(exact(audit, ('schemaVersion', 'mode', 'failed', 'events', 'providerTurns'))
            and integer(audit['schemaVersion'], 2, 2) and audit['mode'] == 'PLAN' and audit['failed'] is False,
            'complete schema-2 host audit required')
    events, turns = array(audit['events'], 16384), array(audit['providerTurns'], 4096)
    recorded_tools = [(tool, tool_record(tool, i)) for i, tool in enumerate(array(evidence['toolExecutions'], 16384), 1)]
    require(exact(scoped, ('schemaVersion', 'mode', 'requests')) and integer(scoped['schemaVersion'], 1, 1)
            and scoped['mode'] == 'PLAN' and len(array(scoped['requests'], 4096)) == len(turns) > 0, 'scoped evidence completeness')
    checks = dict(graph=False, overlapping_lifecycles=False, source_observed=False, branch_results=False,
                  complete_dependency_inputs=False, artifact=False)
    hard = dict(workspace_mutation=False, forbidden_tool_or_path=False)
    allowed = set(oracle['files']) | {'report.json'}
    for path in workspace.rglob('*'):
        if path.is_symlink() or not path.is_file() or path.relative_to(workspace).as_posix() not in allowed:
            hard['workspace_mutation'] = True
    for name, text in oracle['files'].items():
        path = workspace / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size != len(text.encode('utf-8')) or path.read_bytes() != text.encode('utf-8'):
            hard['workspace_mutation'] = True

    # Host cursors/times are one ordering domain. Candidate elapsed times are not that domain.
    previous = -1
    for i, observed in enumerate(events, 1):
        require(exact(observed, ('ordinal', 'hostElapsedNanos', 'eventType', 'event'))
                and integer(observed['ordinal'], i, i) and integer(observed['hostElapsedNanos'])
                and observed['hostElapsedNanos'] >= previous, 'host event order')
        previous = observed['hostElapsedNanos']
    by_scope = collections.defaultdict(list)
    previous_end = -1
    tools_requested = 0
    for i, (turn, request) in enumerate(zip(turns, scoped['requests']), 1):
        require(exact(turn, ('ordinal', 'eventsSeenAtRequest', 'requestStartedNanos', 'responseCompletedNanos',
                             'binding', 'messages', 'tools', 'response')) and integer(turn['ordinal'], i, i)
                and integer(turn['eventsSeenAtRequest'], 0, len(events))
                and integer(turn['requestStartedNanos']) and integer(turn['responseCompletedNanos'])
                and previous_end <= turn['requestStartedNanos'] <= turn['responseCompletedNanos'], 'provider ordering')
        cursor = turn['eventsSeenAtRequest']; start = turn['requestStartedNanos']; end = turn['responseCompletedNanos']
        require((cursor == 0 or events[cursor-1]['hostElapsedNanos'] <= start)
                and (cursor == len(events) or end <= events[cursor]['hostElapsedNanos']), 'request/event timeline mismatch')
        previous_end = end
        binding = turn['binding']
        require(exact(binding, ('scope', 'originSha256', 'inputSha256')) and string(binding['scope'], 256)
                and sha(binding['originSha256']) and sha(binding['inputSha256']), 'binding shape')
        messages = array(turn['messages'], 4096)
        for m in messages:
            message_shape(m)
        users = [m for m in messages if m['role'] == 'user']
        require(len(users) == 1 and isinstance(users[0]['content'], str)
                and digest(users[0]['content']) == binding['inputSha256'], 'actual task user input')
        systems = [m for m in messages if m['role'] == 'system']
        require(systems and messages[:len(systems)] == systems, 'system prefix')
        canonical_tools = []
        for tool in array(turn['tools'], 128):
            require(exact(tool, ('name', 'description', 'parameters')) and string(tool['name'], 256)
                    and (tool['description'] is None or string(tool['description'])), 'tool schema shape')
            canonical_tools.append(dict(name=tool['name'], description=tool['description'], parameters=canonical(tool['parameters'])))
        require(exact(request, ('ordinal', 'binding', 'systemPromptSha256', 'toolSchemaSha256'))
                and integer(request['ordinal'], i, i) and same(request['binding'], binding)
                and request['systemPromptSha256'] == digest(jackson_canonical(systems))
                and request['toolSchemaSha256'] == digest(jackson_canonical(canonical_tools)), 'independent request fingerprint')
        scope_turns = by_scope[binding['scope']]
        if scope_turns:
            prior = scope_turns[0]
            same_binding = (prior['binding']['originSha256'] == binding['originSha256']
                            if binding['scope'] == 'planner' else same(prior['binding'], binding))
            require(same_binding and same(prior['tools'], turn['tools'])
                    and same([m for m in prior['messages'] if m['role'] == 'system'], systems), 'per-task request drift')
        response = turn['response']
        require(exact(response, ('role', 'content', 'reasoningContent', 'toolCalls', 'inputTokens', 'outputTokens',
                                'cachedInputTokens', 'resolvedModel', 'usagePresent'))
                and response['role'] == 'assistant' and (response['content'] is None or string(response['content'], 1_048_576))
                and (response['reasoningContent'] is None or string(response['reasoningContent'], 1_048_576))
                and type(response['usagePresent']) is bool and (response['resolvedModel'] is None or string(response['resolvedModel'], 256))
                and all(integer(response[k], 0, 2**31-1) for k in ('inputTokens', 'outputTokens', 'cachedInputTokens')), 'response schema')
        calls = array(response['toolCalls'], 128)
        for call in calls:
            call_shape(call)
        require(len(set(c['id'] for c in calls)) == len(calls), 'ambiguous batch call IDs')
        tools_requested += len(calls)
        scope_turns.append(turn)
    require(integer(evidence['modelToolCalls'], tools_requested, tools_requested), 'model tool count')
    planners = by_scope.get('planner', [])
    require(1 <= len(planners) <= 32 and planners[0] is turns[0], 'missing/oversized planning chain')
    starts = [i for i, observed in enumerate(events) if observed['eventType'] == 'PlanStarted']
    require(len(starts) in (len(planners), len(planners) - 1)
            and (starts and starts[0] == 0 or not events), 'planning/event count')
    expected_scopes, execution_ids = {'planner'}, set()
    global_tools, unexecuted_calls, previous = [], 0, None
    planning_prefix = '请为以下任务制定执行计划：\n'
    for index, planning in enumerate(planners):
        begin = starts[index] if index < len(starts) else len(events)
        end = starts[index + 1] if index + 1 < len(starts) else len(events)
        require(planning['eventsSeenAtRequest'] == begin and not planning['tools']
                and not planning['response']['toolCalls'] and planning['binding']['originSha256'] == digest(goal)
                and all(m['role'] in ('system', 'user') for m in planning['messages']), 'planning scope/timeline')
        user = next(m['content'] for m in planning['messages'] if m['role'] == 'user')
        require(user.startswith(planning_prefix), 'planning envelope')
        current_goal = user[len(planning_prefix):]
        if index == 0:
            require(current_goal == goal, 'frozen planning goal')
        else:
            require(previous is not None and previous['transition'] is not None, 'replan without native failure trigger')
            transition = previous['transition']
            prefix = '原任务: ' + previous['goal'] + '\n失败原因: '
            suffix = '\n已完成的任务:\n' + ''.join('- ' + task + ': ' + description + '\n'
                      for task, description in transition['completed']) + '\n请制定新的执行计划，避开之前的问题。'
            require(current_goal.startswith(prefix) and current_goal.endswith(suffix)
                    and len(current_goal) >= len(prefix) + len(suffix), 'replanning goal/completed tasks differ')
            # Schema 2 records an exception type, not its message. The intervening
            # failure-reason text is actual provider input, but is not authenticated
            # as the exception's message and is never used as scoring authority.
        if index == len(starts):
            # A final rejected/cyclic/unstarted plan is a valid incomplete result;
            # all earlier work still participates in the global integrity checks.
            checks = {key: False for key in checks}
            break
        plan_events = events[begin:end]
        execution_id = plan_events[0]['event'].get('executionId')
        require(execution_id not in execution_ids, 'reused execution identity')
        execution_ids.add(execution_id)
        current = replay_plan(oracle, workspace, current_goal, planning, plan_events, by_scope, recorded_tools)
        checks = current['checks']
        for key in hard:
            hard[key] |= current['hard'][key]
        global_tools.extend(current['tools']); unexecuted_calls += current['unexecuted']
        expected_scopes.update(current['scopes']); previous = current
    require(set(by_scope) == expected_scopes, 'unattributed provider scopes')
    recorded = [record for _, record in recorded_tools]
    require(collections.Counter(recorded) == collections.Counter(global_tools)
            and len(recorded) + unexecuted_calls == tools_requested, 'global/task tool multiset mismatch')
    return report(checks, hard)


def replan_trigger(nodes, order, descriptions, entered, exited):
    """Reproduce native batch result commit order, not task return wall-clock order."""
    completed, visited = set(), set()
    prior_batch_end = -1
    while True:
        batch = [task for task in order if task not in visited and set(nodes[task]['dependencies']) <= completed]
        if not batch or not set(batch) <= set(entered):
            return None
        if min(entered[task]['ordinal'] for task in batch) <= prior_batch_end:
            return None
        visited.update(batch)
        prior_batch_end = max(exited[task]['ordinal'] for task in batch)
        for task in batch:
            if exited[task]['event']['kind'] == 'RETURNED':
                completed.add(task)
            elif 2 * len(completed) < len(nodes):
                require(set(entered) == visited, 'work after native replan trigger')
                return dict(failedTask=task, completed=[(t, descriptions[t]) for t in nodes if t in completed])


def native_execution_order(nodes):
    """ExecutionPlan's insertion-ordered DFS, independently derived from provider DAG."""
    visited, visiting, order = set(), set(), []
    def visit(task):
        require(task not in visiting, 'cyclic planning graph')
        if task in visited:
            return
        visiting.add(task)
        for dependency in nodes[task]['dependencies']:
            visit(dependency)
        visiting.remove(task); visited.add(task); order.append(task)
    for task in nodes:
        visit(task)
    return order


def replay_plan(oracle, workspace, goal, planning, events, by_scope, recorded_tools):
    branches = source(oracle)
    checks = dict(graph=False, overlapping_lifecycles=False, source_observed=False, branch_results=False,
                  complete_dependency_inputs=False, artifact=False)
    hard = dict(workspace_mutation=False, forbidden_tool_or_path=False)
    normalized = normalize_plan(planning['response']['content'])
    require(normalized is not None, 'invalid graph with unexplained events')
    require(events and events[0]['eventType'] == 'PlanStarted', 'missing PlanStarted')
    plan = events[0]['event']
    require(exact(plan, ('executionId', 'elapsedNanos', 'planId', 'goal', 'tasks', 'executionOrder'))
            and string(plan['planId'], 256) and integer(plan['elapsedNanos'])
            and same(plan['goal'], fingerprint(goal)) and same(plan['tasks'], normalized[0]), 'actual planning graph differs')
    require(str(uuid.UUID(plan['executionId'])) == plan['executionId'], 'execution UUID')
    nodes = {n['taskId']: n for n in normalized[0]}; descriptions = normalized[1]
    order = array(plan['executionOrder'], 128)
    require(len(order) == len(nodes) and set(order) == set(nodes), 'execution order identity')
    require(order == native_execution_order(nodes), 'native execution order differs from provider DAG')
    for n in nodes.values():
        require(all(order.index(d) < order.index(n['taskId']) for d in n['dependencies']), 'topological order')
    roles = {description: task for task, description in descriptions.items()}
    checks['graph'] = len(nodes) == 3 and set(roles) == {'LEFT', 'RIGHT', 'MERGE'}
    if checks['graph']:
        left, right, merge = (roles[r] for r in ('LEFT', 'RIGHT', 'MERGE'))
        checks['graph'] = (not nodes[left]['dependencies'] and not nodes[right]['dependencies']
                           and set(nodes[merge]['dependencies']) == {left, right})
    active, entered, inputs, exited, batches = set(), {}, {}, {}, collections.defaultdict(list)
    clocks = {}
    for observed in events[1:]:
        event = observed['event']; kind = observed['eventType']
        require(isinstance(event, dict) and event.get('executionId') == plan['executionId']
                and event.get('taskId') in nodes and integer(event.get('elapsedNanos')), 'task event identity')
        task = event['taskId']; base = {'executionId', 'elapsedNanos', 'taskId'}
        require(event['elapsedNanos'] >= clocks.get(task, 0), 'task clock moved backwards')
        clocks[task] = event['elapsedNanos']
        if kind == 'TaskEntered':
            require(exact(event, base | {'threadId'}) and integer(event['threadId'], 1)
                    and task not in entered and all(d in exited and exited[d]['event']['kind'] == 'RETURNED'
                                                   for d in nodes[task]['dependencies']), 'task entrance')
            entered[task] = observed; active.add(task)
            continue
        require(task in active, 'event outside task lifecycle')
        if kind == 'TaskInputPrepared':
            require(exact(event, base | {'userText', 'imagePartCount', 'dependencies'}) and task not in inputs
                    and integer(event['imagePartCount'], 0, 0), 'task input event')
            fp(event['userText'])
            deps = array(event['dependencies'], 128)
            require([d.get('taskId') for d in deps] == nodes[task]['dependencies'], 'input dependency IDs')
            for dep in deps:
                require(exact(dep, ('taskId', 'status', 'result')) and dep['status'] == 'COMPLETED'
                        and same(dep['result'], exited[dep['taskId']]['event']['result']), 'dependency digest')
            inputs[task] = observed
        elif kind == 'ToolBatchReturned':
            require(exact(event, base | {'iteration', 'results'}) and task in inputs
                    and integer(event['iteration'], 1, 4096) and (not batches[task]
                    or event['iteration'] > batches[task][-1]['event']['iteration']), 'task batch order')
            for i, result in enumerate(array(event['results'], 128)):
                require(exact(result, ('ordinal', 'callId', 'name', 'arguments', 'result', 'successful', 'timedOut', 'imagePartCount'))
                        and integer(result['ordinal'], i, i) and string(result['callId'], 256) and string(result['name'], 256)
                        and type(result['successful']) is bool and type(result['timedOut']) is bool
                        and integer(result['imagePartCount'], 0, 0), 'tool result event')
                fp(result['arguments']); fp(result['result'])
            batches[task].append(observed)
        elif kind == 'TaskExited':
            require(exact(event, base | {'kind', 'result', 'exceptionType'}) and event['kind'] in ('RETURNED', 'THREW'),
                    'complete task exit required')
            if event['kind'] == 'RETURNED':
                require(event['exceptionType'] is None and task in inputs, 'returned task with exception/missing input')
            else:
                require(string(event['exceptionType'], 256) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]*', event['exceptionType'])
                        and same(event['result'], fingerprint(None)), 'exception task exit')
            fp(event['result']); exited[task] = observed; active.remove(task)
        else:
            raise ValueError('unknown/repeated plan event')
    require(not active and set(entered) == set(exited), 'incomplete task lifecycle')
    scopes = {plan['executionId'] + ':' + t for t in entered if by_scope.get(plan['executionId'] + ':' + t)}
    global_tools, observed_rows, outputs = [], collections.defaultdict(set), {}
    unexecuted_calls = 0
    all_inputs_match = True
    write_contents = []
    for task, node in nodes.items():
        if task not in entered:
            outputs[task] = None; all_inputs_match = False
            continue
        task_turns = by_scope.get(plan['executionId'] + ':' + task, [])
        threw = exited[task]['event']['kind'] == 'THREW'
        require(task_turns or threw, 'returned task without provider response')
        require(not task_turns or task in inputs, 'provider request without prepared input')
        task_batches = {b['event']['iteration']: b for b in batches[task]}
        seen_batches = set(); final_text = None; final_seen = False; returned_tools = []
        for local_index, turn in enumerate(task_turns, 1):
            require(inputs[task]['ordinal'] <= turn['eventsSeenAtRequest'] < exited[task]['ordinal']
                    and turn['binding']['originSha256'] == digest(planning['response']['content']), 'request outside observed task')
            user = next(m['content'] for m in turn['messages'] if m['role'] == 'user')
            require(same(inputs[task]['event']['userText'], fingerprint(user)), 'task/provider input fingerprint')
            if local_index == 1:
                require(all(m['role'] in ('system', 'user') for m in turn['messages']), 'unexpected initial task history')
            response = turn['response']; calls = response['toolCalls']
            if not calls:
                require(local_index == len(task_turns), 'request after final task response')
                # Native Plan may return accumulated tool results when the final model body is
                # blank. Null without prior tools is also a real (usually unsuccessful) return.
                final_seen = True
                final_text = (''.join(text + '\n' for text in returned_tools).strip(''.join(chr(i) for i in range(33)))
                              if returned_tools and java_blank(response['content']) else response['content'])
                if not threw:
                    require(same(exited[task]['event']['result'], fingerprint(final_text)), 'task exit/actual response mismatch')
                continue
            terminal = local_index == len(task_turns)
            if local_index not in task_batches:
                require(terminal and threw, 'unobserved tool batch')
                # A local exception before tool execution is not an evidence failure. The
                # final global multiset must still prove that none of these calls executed.
                unexecuted_calls += len(calls)
                for call in calls:
                    try:
                        args = parse(call['function']['arguments'])
                    except ValueError:
                        args = None
                    path_name = tool_path(args, workspace); name = call['function']['name']; role = descriptions[task]
                    if not (name == 'read_file' and path_name == {'LEFT': 'left.csv', 'RIGHT': 'right.csv'}.get(role)
                            and role in ('LEFT', 'RIGHT') or role == 'MERGE' and path_name == 'report.json'
                            and name in ('read_file', 'write_file')):
                        hard['forbidden_tool_or_path'] = True
                continue
            require(not terminal or threw, 'returned task without final response')
            batch = task_batches[local_index]; seen_batches.add(local_index)
            following = None if terminal else task_turns[local_index]
            require(turn['eventsSeenAtRequest'] < batch['ordinal'] <=
                    (exited[task]['ordinal'] - 1 if terminal else following['eventsSeenAtRequest']), 'tool batch/provider timeline')
            results = batch['event']['results']; require(len(results) == len(calls), 'batch result count')
            assistant = dict(role='assistant', content=response['content'], reasoningContent=response['reasoningContent'],
                             toolCalls=calls, toolCallId=None, contentParts=[])
            prefix = turn['messages'] + [assistant]
            if following is not None:
                require(same(following['messages'][:len(prefix)], prefix)
                        and len(following['messages']) == len(prefix) + len(calls), 'changed/omitted task conversation')
            for call_index, (call, result) in enumerate(zip(calls, results)):
                require(result['callId'] == call['id'] and result['name'] == call['function']['name']
                        and same(result['arguments'], fingerprint(call['function']['arguments'])), 'task-local tool correlation')
                text = None
                if terminal:
                    global_tools.append(terminal_tool_record(call, result, recorded_tools))
                else:
                    seen = following['messages'][len(prefix) + call_index]
                    require(seen['role'] == 'tool' and seen['toolCallId'] == call['id'] and not seen['toolCalls']
                            and same(result['result'], fingerprint(seen['content'])), 'task-local tool correlation')
                    text = seen['content']; require(isinstance(text, str), 'tool output text')
                    returned_tools.append(text)
                    global_tools.append((call['id'], call['function']['name'], call['function']['arguments'], digest(text),
                                         len(text.encode('utf-16-le')) // 2, result['successful'], result['timedOut']))
                try:
                    args = parse(call['function']['arguments'])
                except ValueError:
                    args = None
                name = call['function']['name']; role = descriptions[task]
                source_name = {'LEFT': 'left.csv', 'RIGHT': 'right.csv'}.get(role)
                path_name = tool_path(args, workspace)
                valid_read = (name == 'read_file' and path_name == source_name
                              and source_name is not None)
                valid_write = name == 'write_file' and role == 'MERGE' and path_name == 'report.json'
                valid_verify = name == 'read_file' and role == 'MERGE' and path_name == 'report.json'
                if not (valid_read or valid_write or valid_verify):
                    hard['forbidden_tool_or_path'] = True
                if valid_read and not terminal and result['successful'] and not result['timedOut']:
                    expected_read, rows = read_result(source_name, oracle['files'][source_name], args)
                    if text == expected_read:
                        observed_rows[role].update(rows)
                if valid_write and result['successful'] and not result['timedOut'] and string(args.get('content')):
                    write_contents.append(args['content'])
        require(set(task_batches) == seen_batches and (final_seen or threw), 'unmatched tool batches/final response')
        outputs[task] = None if threw else final_text
    # Build the actual task context from provider outputs, not from observed digest claims.
    for task, node in nodes.items():
        expected = '总目标：' + goal + '\n当前任务：' + descriptions[task] + '\n'
        if not node['dependencies']:
            expected += '依赖任务：无\n'
        else:
            expected += '依赖任务结果：\n'
            for dep in node['dependencies']:
                expected += '- ' + dep + ' / ' + descriptions[dep] + ' / 状态=COMPLETED\n'
                if not java_blank(outputs[dep]):
                    expected += outputs[dep] + '\n'
        expected += '请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。'
        task_turns = by_scope.get(plan['executionId'] + ':' + task, [])
        if task_turns:
            actual = next(m['content'] for m in task_turns[0]['messages'] if m['role'] == 'user')
            all_inputs_match &= actual == expected
        else:
            all_inputs_match = False  # Prepared input is not proof it reached the model.
    checks['source_observed'] = all(observed_rows[side.upper()] == set(range(len(oracle['files'][side + '.csv'].splitlines())))
                                    for side in ('left', 'right'))
    checks['complete_dependency_inputs'] = checks['graph'] and all_inputs_match
    if len(nodes) == 3 and set(roles) == {'LEFT', 'RIGHT', 'MERGE'} and set(entered) == set(nodes):
        left, right, merge = (roles[r] for r in ('LEFT', 'RIGHT', 'MERGE'))
        checks['overlapping_lifecycles'] = (max(entered[left]['hostElapsedNanos'], entered[right]['hostElapsedNanos'])
            < min(exited[left]['hostElapsedNanos'], exited[right]['hostElapsedNanos'])
            and entered[left]['event']['threadId'] != entered[right]['event']['threadId']
            and entered[merge]['hostElapsedNanos'] > max(exited[left]['hostElapsedNanos'], exited[right]['hostElapsedNanos']))
        try:
            checks['branch_results'] = all(same(parse(outputs[roles[s.upper()]]), branches[s]) for s in ('left', 'right'))
        except (ValueError, TypeError):
            pass
    path = workspace / 'report.json'
    expected_report = dict(left=branches['left'], right=branches['right'],
                           combined_cents=branches['left']['sum_cents'] + branches['right']['sum_cents'])
    if path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536:
        try:
            text = path.read_text(encoding='utf-8')
            checks['artifact'] = same(parse(text), expected_report) and text in write_contents
        except (ValueError, UnicodeError):
            pass
    return dict(checks=checks, hard=hard, tools=global_tools, unexecuted=unexecuted_calls, scopes=scopes,
                goal=goal, transition=replan_trigger(nodes, order, descriptions, entered, exited))


def main():
    require(len(sys.argv) == 4, 'arguments')
    oracle, workspace, evidence = map(pathlib.Path, sys.argv[1:])
    require(workspace.is_dir() and not workspace.is_symlink(), 'workspace')
    print(compact(qualify(load(oracle, 131072), load(evidence, 16777216), workspace, digest(oracle.read_bytes()))))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('E1 source/evidence invalid; no candidate verdict', file=sys.stderr)
        raise SystemExit(2)
