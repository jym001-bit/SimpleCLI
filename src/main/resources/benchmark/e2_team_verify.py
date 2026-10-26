"""Independent E2 Team diagnostic verifier. No Java, network or model calls.

Replays a schema-1 host team-audit.json: rebuilds the run lifecycle from raw
digest-only events, re-derives file-write attribution from actual provider
requests plus observed post-policy tool batches, and cross-checks the audit's
own attribution section. Self-contradictory or incomplete evidence is
evaluation-invalid with no numeric score; consistent but unsuccessful Candidate
behavior is a valid failure. This is NOT a formal scoring adapter.
"""
import hashlib
import json
import pathlib
import re
import sys

SCOPE = re.compile(r'^team:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')


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


def load(path, limit):
    require(path.is_file() and not path.is_symlink(), 'missing evidence file')
    require(path.stat().st_size <= limit, 'evidence file exceeds limit')
    return parse(path.read_text(encoding='utf-8'))


def safe_member(workspace, relative):
    require(isinstance(relative, str) and relative and not relative.startswith('/')
            and '\\' not in relative, 'unsafe contract path')
    parts = relative.split('/')
    require(all(part not in ('', '.', '..') for part in parts), 'unsafe contract path')
    target = workspace
    for part in parts:
        target = target / part
    return target


def identity_of(scope, activations):
    require(scope in activations, 'attempt scope has no entered activation')
    return activations[scope]


def qualify(oracle, evidence, workspace, oracle_sha, evidence_bytes):
    require(isinstance(oracle, dict)
            and (oracle.get('contractVersion') == 1 or oracle.get('schemaVersion') == 1),
            'unsupported contract')
    # The registered prototype verifies a v5 evidence envelope carrying the host audit;
    # a bare schema-1 team audit is still accepted for development diagnostics.
    if isinstance(evidence, dict) and not evidence.get('events') \
            and isinstance(evidence.get('teamAudit'), dict):
        evidence = evidence['teamAudit']
    require(isinstance(evidence, dict) and evidence.get('schemaVersion') == 1
            and evidence.get('mode') == 'TEAM', 'unsupported evidence schema')
    events = evidence.get('events')
    attempts = evidence.get('providerAttempts')
    require(isinstance(events, list) and isinstance(attempts, list), 'evidence shape')
    invalid = []

    # --- lifecycle rebuilt from raw digest-only events; the Java flags are never trusted ---
    activations = {}
    run_starts = run_exits = plan_prepared = approved_reviews = 0
    tool_batches = {}
    ordinal = 0
    for event in events:
        require(isinstance(event, dict), 'event shape')
        ordinal += 1
        require(event.get('ordinal') == ordinal, 'event ordinals must be sequential')
        kind = event.get('eventType')
        body = event.get('event')
        require(isinstance(body, dict), 'event body')
        if kind == 'RunStarted':
            run_starts += 1
        elif kind == 'RunExited':
            run_exits += 1
        elif kind == 'PlanPrepared':
            plan_prepared += 1
        elif kind == 'ActivationEntered':
            identity = body.get('activation')
            require(isinstance(identity, dict) and identity.get('activationId'), 'activation identity')
            scope = 'team:' + identity['activationId']
            require(scope not in activations, 'duplicate activation entry')
            activations[scope] = identity
        elif kind == 'ActivationExited':
            identity = body.get('activation')
            scope = 'team:' + identity['activationId']
            require(scope in activations, 'exit for an unobserved activation')
            require(not activations[scope].get('_exited'), 'duplicate activation exit')
            activations[scope]['_exited'] = True
        elif kind == 'ToolBatchReturned':
            identity = body.get('activation')
            scope = 'team:' + identity['activationId']
            for result in body.get('results') or []:
                key = (scope, result.get('callId'))
                require(key not in tool_batches, 'duplicate observed tool result')
                tool_batches[key] = result
        elif kind == 'ReviewEvaluated':
            if body.get('decision') == 'APPROVED':
                approved_reviews += 1
    if run_starts != 1:
        invalid.append('run_start_missing_or_duplicated')
    if run_exits != 1:
        invalid.append('run_exit_missing_or_duplicated')
    for scope, identity in activations.items():
        require(SCOPE.match(scope) and identity.get('role') in ('PLANNER', 'WORKER', 'REVIEWER'),
                'invalid activation identity')
        if not identity.get('_exited'):
            invalid.append('activation_never_exited')

    # --- re-derive writes from actual provider requests + observed post-policy batches ---
    derived = {}
    for attempt in attempts:
        require(isinstance(attempt, dict), 'attempt shape')
        scope = attempt.get('scope')
        require(isinstance(scope, str) and SCOPE.match(scope), 'invalid attempt scope')
        identity_of(scope, activations)
        require(attempt.get('providerDispatched') is True and attempt.get('delivered') is True,
                'attempt was not fully dispatched and delivered')
        if attempt.get('failureType') is not None:
            invalid.append('attempt_recorded_provider_failure')
        response = attempt.get('returnedResponse')
        require(isinstance(response, dict), 'attempt without returned response')
        for call in response.get('toolCalls') or []:
            function = call.get('function') or {}
            if function.get('name') != 'write_file':
                continue
            key = (scope, call.get('id'))
            require(key not in derived, 'duplicate write tool call')
            arguments_text = function.get('arguments')
            path = None
            try:
                parsed = parse(arguments_text)
                if isinstance(parsed, dict) and isinstance(parsed.get('path'), str) and parsed['path']:
                    path = parsed['path']
            except ValueError:
                path = None
            derived[key] = (path, arguments_text)
    rederived = []
    for (scope, call_id), (path, arguments_text) in derived.items():
        batch = tool_batches.get((scope, call_id))
        if batch is None:
            invalid.append('write_without_observed_batch')
            continue
        fingerprint = batch.get('arguments') or {}
        if fingerprint.get('present') is True:
            require(isinstance(arguments_text, str)
                    and digest(arguments_text) == fingerprint.get('sha256'),
                    'tool batch arguments fingerprint drift')
        rederived.append(dict(scope=scope, toolCallId=call_id, path=path,
                              successful=batch.get('successful') is True))

    # --- the audit's own attribution section must match the independent re-derivation ---
    section = evidence.get('writeAttributions')
    require(isinstance(section, list), 'attribution section shape')
    def key_of(item):
        return (item.get('scope'), item.get('role'), item.get('stepId'), item.get('attempt'),
                item.get('toolCallId'), item.get('path'), item.get('successful') is True)
    derived_keys = sorted((item['scope'],
                           identity_of(item['scope'], activations).get('role'),
                           identity_of(item['scope'], activations).get('stepId'),
                           identity_of(item['scope'], activations).get('attempt'),
                           item['toolCallId'], item['path'], item['successful']) for item in rederived)
    section_keys = sorted(key_of(item) for item in section)
    attribution_match = same(derived_keys, section_keys)
    if not attribution_match:
        invalid.append('attribution_section_mismatch')

    successful_by_path = {}
    for item in rederived:
        if item['successful'] and item['path']:
            # The audit section records bare activation ids; derive the same identity form.
            successful_by_path.setdefault(item['path'], set()).add(item['scope'].split(':', 1)[1])
    rederived_conflicts = sorted((path, sorted(scopes))
                                 for path, scopes in successful_by_path.items() if len(scopes) > 1)
    section_conflicts = evidence.get('writeConflicts')
    require(isinstance(section_conflicts, list), 'conflict section shape')
    section_conflict_keys = sorted((item.get('path'), sorted(item.get('activationIds') or []))
                                   for item in section_conflicts)
    if not same(rederived_conflicts, section_conflict_keys):
        invalid.append('conflict_section_mismatch')

    # --- contract checks; failures here are valid Candidate failures, not evidence faults ---
    expected = oracle.get('expectedWrites')
    require(isinstance(expected, list), 'contract expectedWrites')
    successful_derived = [dict(path=item['path'], role=identity_of(item['scope'], activations).get('role'))
                          for item in rederived if item['successful'] and item['path']]
    expected_ok = len(successful_derived) == len(expected) and all(
        any(item['path'] == want.get('path') and item['role'] == want.get('role')
            for item in successful_derived) for want in expected)
    conflict_free = not rederived_conflicts if oracle.get('requireNoConflicts') is True else True
    files_ok = True
    for relative in oracle.get('requireWorkspaceFiles') or []:
        target = safe_member(workspace, relative)
        if not (target.is_file() and not target.is_symlink() and target.stat().st_size <= 1_048_576):
            files_ok = False
    minimum_reviews = oracle.get('minApprovedReviews')
    require(isinstance(minimum_reviews, int) and minimum_reviews >= 0, 'contract minApprovedReviews')
    reviews_ok = approved_reviews >= minimum_reviews

    doc_ok = True
    if oracle.get('docConsumesDependencies') is True:
        doc_path = oracle.get('docPath')
        require(isinstance(doc_path, str) and doc_path, 'contract docPath')
        target = safe_member(workspace, doc_path)
        try:
            note = target.read_text(encoding='utf-8')
        except (OSError, UnicodeError):
            note = None
        step_workers = {}
        step_dependencies = {}
        final_content = {}
        for event in events:
            body = event.get('event') or {}
            kind = event.get('eventType')
            if kind == 'ActivationEntered' and (body.get('activation') or {}).get('role') == 'WORKER':
                step_workers.setdefault(body['activation'].get('stepId'), []).append(
                    'team:' + body['activation']['activationId'])
            elif kind == 'StepEntered':
                step_dependencies[body.get('stepId')] = [d.get('stepId') for d in body.get('dependencies') or []]
        for attempt in attempts:
            scope = attempt.get('scope')
            if attempt.get('delivered') is True:
                final_content[scope] = (attempt.get('returnedResponse') or {}).get('content')
        if not isinstance(note, str):
            doc_ok = False
        else:
            for step_id, dependencies in step_dependencies.items():
                if not dependencies or step_id not in step_workers:
                    continue
                for dependency in dependencies:
                    dep_scopes = step_workers.get(dependency) or []
                    text = final_content.get(dep_scopes[-1]) if dep_scopes else None
                    if not isinstance(text, str) or text not in note:
                        doc_ok = False

    # The prompt forbids creating other files: the workspace inventory must be exactly
    # the frozen fixtures plus the contracted outputs. Extra files are a valid failure.
    allowed = set(oracle.get('files') or {}) | set(oracle.get('requireWorkspaceFiles') or [])
    present = set()
    for candidate in workspace.rglob('*'):
        relative = candidate.relative_to(workspace).as_posix()
        if candidate.is_symlink():
            present.add(relative)
        elif candidate.is_file():
            present.add(relative)
    unrequested_ok = present == allowed

    checks = dict(attribution_consistent=attribution_match,
                  expected_writes=expected_ok,
                  no_conflicts=conflict_free,
                  workspace_files=files_ok,
                  reviews_approved=reviews_ok,
                  doc_consumes_dependencies=doc_ok,
                  no_unrequested_files=unrequested_ok)
    evidence_consistent = not invalid and evidence.get('failed') is False
    report = dict(schemaVersion=1, oracleSha256=oracle_sha,
                  evidenceSha256=digest(evidence_bytes),
                  checks=checks, publishable=False)
    if not evidence_consistent:
        report['evaluationInvalid'] = True
        report['invalidReasons'] = sorted(set(invalid))
        report['diagnosticSatisfied'] = False
        report['diagnosticScore'] = None
        return report
    report['evaluationInvalid'] = False
    report['invalidReasons'] = []
    report['diagnosticSatisfied'] = all(checks.values())
    report['diagnosticScore'] = 100 if report['diagnosticSatisfied'] else 0
    return report


def main():
    require(len(sys.argv) == 4, 'arguments')
    oracle_path, workspace, evidence_path = map(pathlib.Path, sys.argv[1:])
    require(workspace.is_dir() and not workspace.is_symlink(), 'workspace')
    oracle_bytes = oracle_path.read_bytes()
    evidence_bytes = evidence_path.read_bytes()
    print(compact(qualify(parse(oracle_bytes.decode('utf-8')), parse(evidence_bytes.decode('utf-8')),
                          workspace, digest(oracle_bytes), evidence_bytes)))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('E2 source/evidence invalid; no candidate verdict', file=sys.stderr)
        raise SystemExit(2)
