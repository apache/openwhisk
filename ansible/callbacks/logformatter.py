from __future__ import (absolute_import, division, print_function)
__metaclass__ = type

from ansible.plugins.callback import CallbackBase
import sys
import json

class CallbackModule(CallbackBase):

    def __init__(self):
        super(CallbackModule, self).__init__()

    def emit(self, host, category, data):
        if type(data) == dict:
            cmd = data['cmd'] if 'cmd' in data else None
            stdout = data['stdout'] if 'stdout' in data else None
            stderr = data['stderr'] if 'stderr' in data else None
            reason = data['reason'] if 'reason' in data else None

            if cmd:
                print(hilite('[%s]\n> %s' % (category, cmd), category))
            if reason:
                print(hilite(reason, category))
            if stdout:
                print(hilite(stdout, category))
            if stderr:
                print(hilite(stderr, category))

    def runner_on_failed(self, host, res, ignore_errors=False):
        self.emit(host, 'FAILED', res)

    def runner_on_ok(self, host, res):
        pass

    def runner_on_skipped(self, host, item=None):
        self.emit(host, 'SKIPPED', '...')

    def runner_on_unreachable(self, host, res):
        self.emit(host, 'UNREACHABLE', res)

    def runner_on_async_failed(self, host, res, jid):
        self.emit(host, 'FAILED', res)


def hilite(msg, status):
    def supportsColor():
        if (sys.platform != 'win32' or 'ANSICON' in os.environ) and sys.stdout.isatty():
            return True
        else:
            return False

    if supportsColor():
        attr = []
        if status == 'FAILED':
            attr.append('31') # red
        else:
            attr.append('1') # bold
        return '\x1b[%sm%s\x1b[0m' % (';'.join(attr), msg)
    else:
        return msg
