"""Whisk Utility methods.

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


import os
import json
import sys
if sys.version_info.major >= 3:
    from http.client import HTTPConnection, HTTPSConnection, IncompleteRead
    from urllib.parse import urlparse
else:
    from httplib import HTTPConnection, HTTPSConnection, IncompleteRead
    from urlparse import urlparse
import ssl
import base64
import socket


# global configurations, can control whether to allow untrusted certificates
# on HTTPS connections

verify_cert = os.getenv('DB_VERIFY_CERT') is None or os.getenv('DB_VERIFY_CERT').lower() != 'false'
httpRequestProps = {'secure': verify_cert}

def request(method, urlString, body = '', headers = {}, auth = None, verbose = False, https_proxy = os.getenv('https_proxy', None), timeout = 60):
    url = urlparse(urlString)
    if url.scheme == 'http':
        conn = HTTPConnection(url.netloc, timeout = timeout)
    else:
        if httpRequestProps['secure'] or not hasattr(ssl, '_create_unverified_context'):
            conn = HTTPSConnection(url.netloc if https_proxy is None else https_proxy, timeout = timeout)
        else:
            conn = HTTPSConnection(url.netloc if https_proxy is None else https_proxy, context=ssl._create_unverified_context(), timeout = timeout)
        if https_proxy:
            conn.set_tunnel(url.netloc)

    if auth is not None:
        auth = base64.b64encode(auth.encode()).decode()
        headers['Authorization'] = 'Basic %s' % auth

    if verbose:
        print('========')
        print('REQUEST:')
        print('%s %s' % (method, urlString))
        print('Headers sent:')
        print(getPrettyJson(headers))
        if body != '':
            print('Body sent:')
            print(body)

    try:
        conn.request(method, urlString, body, headers)
        res = conn.getresponse()
        body = ''
        try:
            body = res.read()
        except IncompleteRead as e:
            body = e.partial

        # patch the read to return just the body since the normal read
        # can only be done once
        res.read = lambda: body

        if verbose:
            print('--------')
            print('RESPONSE:')
            print('Got response with code %s' % res.status)
            print('Body received:')
            print(res.read())
            print('========')
        return res
    except socket.timeout:
        return ErrorResponse(status = 500, error = 'request timed out at %d seconds' % timeout)
    except Exception as e:
        return ErrorResponse(status = 500, error = str(e))


def getPrettyJson(obj):
    return json.dumps(obj, sort_keys=True, indent=4, separators=(',', ': '))


# class to normalize responses for exceptions with no HTTP response for canonical error handling
class ErrorResponse:
    def __init__(self, status, error):
        self.status = status
        self.error = error

    def read(self):
        return self.error
