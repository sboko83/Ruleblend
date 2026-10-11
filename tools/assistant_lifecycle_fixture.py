#!/usr/bin/env python3
"""Offline Claude-shaped executable: records native prefill, never contacts a model."""
import json
import os
from pathlib import Path
import signal
import sys
import termios
import tty


def main():
    prompt = sys.argv[sys.argv.index('--prefill') + 1]
    events = Path.cwd() / 'events.jsonl'

    def record(kind, **values):
        with events.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps({'event': kind, **values}, ensure_ascii=False) + '\n')

    saved = termios.tcgetattr(sys.stdin.fileno())
    tty.setraw(sys.stdin.fileno())
    if '(ID: stubborn)' in prompt:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    record('ready', pid=os.getpid(), argv=sys.argv[1:], cwd=os.getcwd(), tty=sys.stdin.isatty(),
           term=os.environ.get('TERM'), inherited=os.environ.get('RULEBLEND_LIFECYCLE_TEST'))
    try:
        print('READY · native editable draft: ' + prompt, end='\r\n', flush=True)
        while True:
            chunk = os.read(sys.stdin.fileno(), 1024)
            record('input', text=chunk.decode('utf-8', errors='replace'))
            if b'\x04' in chunk:
                record('exit')
                return
    finally:
        termios.tcsetattr(sys.stdin.fileno(), termios.TCSANOW, saved)


if __name__ == '__main__':
    main()
