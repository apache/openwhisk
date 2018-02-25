"""Python callback for highlighting Ansible logs.

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""
from __future__ import (absolute_import, division, print_function)
import os
import sys
import textwrap
from ansible.plugins.callback import CallbackBase
__metaclass__ = type


class CallbackModule(CallbackBase):
    """."""

    def __init__(self):
        """Initialize superclass."""
        super(CallbackModule, self).__init__()

    def emit(self, host, category, data):
        """Emit colorized output based upon data contents."""
        if type(data) == dict:
            cmd = data['cmd'] if 'cmd' in data else None
            msg = data['msg'] if 'msg' in data else None
            stdout = data['stdout'] if 'stdout' in data else None
            stderr = data['stderr'] if 'stderr' in data else None
            reason = data['reason'] if 'reason' in data else None

            print()
            if cmd:
                print(hilite('[%s]\n> %s' % (category, cmd), category, wrap = False))
            if reason:
                print(hilite(reason, category))
            if msg:
                print(hilite(msg, category))
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


def hilite(msg, status, wrap = True):
    """Highlight message."""
    def supports_color():
        if ((sys.platform != 'win32' or 'ANSICON' in os.environ) and
           sys.stdout.isatty()):
            return True
        else:
            return False

    if supports_color():
        attr = []
        if status == 'FAILED':
            # red
            attr.append('31')
        else:
            # bold
            attr.append('1')
        text = '\x1b[%sm%s\x1b[0m' % (';'.join(attr), msg)
    else:
        text = msg
    return textwrap.fill(text, 80) if wrap else text
