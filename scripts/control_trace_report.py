#!/usr/bin/env python3
"""Inspect one invocation's monotonic spans without double-counting parents."""
import argparse
import json
from collections import defaultdict
from pathlib import Path


def summarize(trace):
    if trace.get('clock_domain') != 'monotonic_process':
        raise ValueError('One invocation-local monotonic clock is required')
    total = trace['total_ns']
    if type(total) is not int or total <= 0:
        raise ValueError('Positive monotonic total required')
    spans = trace['spans']
    by_id = {}
    children = defaultdict(list)
    for s in spans:
        if any(type(s[k]) is not int for k in ('id', 'parent', 'start_ns', 'duration_ns')):
            raise ValueError('Integer span coordinates required')
        if s['id'] < 0 or s['id'] in by_id or s['start_ns'] < 0 or s['duration_ns'] < 0:
            raise ValueError('Invalid/duplicate span')
        if s['start_ns'] + s['duration_ns'] > total or not isinstance(s.get('phase'), str):
            raise ValueError('Span outside invocation or missing phase')
        by_id[s['id']] = s
        children[s['parent']].append(s)
    for s in spans:
        if s['parent'] != -1:
            parent = by_id.get(s['parent'])
            if not parent or parent['id'] >= s['id'] or s['start_ns'] < parent['start_ns'] or s['start_ns'] + s['duration_ns'] > parent['start_ns'] + parent['duration_ns']:
                raise ValueError('Invalid parent containment/order')
    for siblings in children.values():
        previous_end = -1
        for s in sorted(siblings, key=lambda s: s['start_ns']):
            if s['start_ns'] < previous_end:
                raise ValueError('Overlapping sibling spans cannot be summed')
            previous_end = s['start_ns'] + s['duration_ns']
    exclusive = defaultdict(int)
    inclusive = defaultdict(int)
    for s in spans:
        inclusive[s['phase']] += s['duration_ns']
        exclusive[s['phase']] += s['duration_ns'] - sum(c['duration_ns'] for c in children[s['id']])
    untraced = total - sum(s['duration_ns'] for s in children[-1])
    return dict(correlation_id=trace.get('correlation_id'), clock_domain=trace['clock_domain'],
                total_ms=total/1e6, span_count=len(spans), untraced_ms=untraced/1e6,
                exclusive_ms=dict(sorted(((k,v/1e6) for k,v in exclusive.items()), key=lambda item:-item[1])),
                inclusive_ms={k:v/1e6 for k,v in inclusive.items()},
                voice_e2e='NOT_TESTED', transport='NOT_TESTED', ai_planning='NOT_TESTED',
                limitation='Single historical invocation, not a matched performance comparison. Inclusive phases overlap; sum exclusive only.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    result = summarize(data.get('trace', data))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
