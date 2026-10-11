#!/usr/bin/env python3
"""Offline interactive child for the opt-in terminal feasibility harness."""

import json
import os
from pathlib import Path
import signal
import sys
import termios
import tty


def main():
    events = Path(sys.argv[1])
    saved = termios.tcgetattr(sys.stdin.fileno())

    def record(kind, **values):
        with events.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps({'event': kind, **values}, ensure_ascii=False) + '\n')

    def output(text):
        sys.stdout.write(text)
        sys.stdout.flush()

    def resize(*_):
        size = os.get_terminal_size()
        record('resize', columns=size.columns, rows=size.lines)

    signal.signal(signal.SIGWINCH, resize)
    tty.setraw(sys.stdin.fileno())
    record('ready', pid=os.getpid(), tty=sys.stdin.isatty())
    try:
        output('\x1b[?1049h\x1b[?2004h\x1b[?1000h\x1b[?1006h\x1b[2J\x1b[H'
               'Ruleblend offline terminal fixture\r\n'
               'UTF-8: Привет · 日本語 · 😀\r\n'
               'Type a message. Ctrl+C interrupts; Ctrl+D exits.\r\n'
               'READY\r\n❯ ')
        buffer = bytearray()
        paste = None
        while True:
            chunk = os.read(sys.stdin.fileno(), 4096)
            if not chunk:
                break
            buffer.extend(chunk)
            while buffer:
                if paste is not None:
                    end = buffer.find(b'\x1b[201~')
                    if end < 0:
                        break
                    text = bytes(buffer[:end]).decode('utf-8')
                    del buffer[:end + 6]
                    record('paste', text=text)
                    output(text.replace('\n', '\r\n'))
                    paste = None
                elif buffer.startswith(b'\x1b[200~'):
                    del buffer[:6]
                    paste = True
                elif buffer[0] == 27 and len(buffer) < 6:
                    break
                else:
                    value = buffer.pop(0)
                    if value == 4:
                        record('exit')
                        return
                    if value == 3:
                        record('interrupt')
                        output('^C\r\n❯ ')
                    elif value in (10, 13):
                        record('submit')
                        output('\r\nFixture accepted input locally.\r\n❯ ')
                    elif value == 12:
                        for i in range(3000):
                            output(f'output {i}\r\n')
                        record('flood', lines=3000)
                    else:
                        os.write(sys.stdout.fileno(), bytes([value]))
    finally:
        output('\x1b[?1006l\x1b[?1000l\x1b[?2004l\x1b[?1049l')
        termios.tcsetattr(sys.stdin.fileno(), termios.TCSANOW, saved)


if __name__ == '__main__':
    main()
