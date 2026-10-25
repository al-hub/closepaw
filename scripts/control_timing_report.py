#!/usr/bin/env python3
"""Read-only #161 Android timing analysis; no phone access, clock mixing or performance claims."""
import argparse
import json
from pathlib import Path


def integer(value):
    if type(value) is not int or value < 0:
        raise ValueError('nonnegative integer required')
    return value


def summarize(trace):
    if trace.get('clock_domain') != 'android_elapsed_realtime_ns':
        raise ValueError('Android elapsedRealtime clock required')
    if trace.get('buffer_complete') is not True or integer(trace.get('dropped_events')) != 0:
        raise ValueError('truncated timing cannot support performance conclusions')
    events = trace['events']
    if not events:
        raise ValueError('no timing events')
    groups = {}
    previous = -1
    dispatches = []
    for e in events:
        at = integer(e['at_ns'])
        key = integer(e['id'])
        integer(e['step'])
        if at < previous:
            raise ValueError('clock moved backwards')
        previous = at
        group = groups.setdefault(key, {})
        event = e['event']
        if event in group:
            raise ValueError('duplicate lifecycle event')
        group[event] = e
        if event == 'PROBE_DISPATCH':
            dispatches.append(at)
    def interval(group, start, end):
        if start not in group or end not in group:
            return None
        delta = group[end]['at_ns'] - group[start]['at_ns']
        if delta < 0:
            raise ValueError('reversed lifecycle')
        return delta / 1e6
    probes = []
    stages = []
    for key, group in groups.items():
        if 'PROBE_QUEUED' in group:
            callbacks = [n for n in ('PROBE_CALLBACK_OK', 'PROBE_CALLBACK_FAILED', 'PROBE_EXCEPTION') if n in group]
            if len(callbacks) > 1:
                raise ValueError('conflicting callback outcomes')
            probes.append(dict(id=key, step=group['PROBE_QUEUED']['step'],
                slot_wait_ms=interval(group, 'PROBE_QUEUED', 'PROBE_SLOT_READY'),
                main_queue_ms=interval(group, 'PROBE_SLOT_READY', 'MAIN_ENTERED'),
                main_before_dispatch_ms=interval(group, 'MAIN_ENTERED', 'PROBE_DISPATCH'),
                api_callback_ms=interval(group, 'PROBE_DISPATCH', callbacks[0]) if callbacks else None,
                callback=callbacks[0] if callbacks else 'NOT_OBSERVED',
                cancelled='PROBE_CANCELLED' in group))
        if 'STAGE_START' in group:
            stages.append(dict(id=key, step=group['STAGE_START']['step'],
                phase=group['STAGE_START'].get('phase', 'other'),
                inclusive_ms=interval(group, 'STAGE_START', 'STAGE_END')))
    return dict(clock_domain=trace['clock_domain'], probe_count=len(dispatches), probes=probes,
        actual_dispatch_gaps_ms=[(b-a)/1e6 for a, b in zip(dispatches, dispatches[1:])],
        stages=stages, aggregation='inclusive overlapping stage durations; never add all stages',
        physical_ui_render='NOT_MEASURED; guarded snapshot availability is not pixel presentation',
        event_delivery='NOT_MEASURED', cpu_memory='NOT_MEASURED', voice_e2e='NOT_TESTED',
        speedup='NOT_COMPUTED; matched device cohorts required')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = json.loads(args.input.read_text())
    result = summarize(raw.get('android_timing', raw))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
