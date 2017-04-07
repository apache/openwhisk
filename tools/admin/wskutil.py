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
import httplib
import ssl
import base64
from urlparse import urlparse

# global configurations, can control whether to allow untrusted certificates
# on HTTPS connections
httpRequestProps = {'secure': True}

def request(method, urlString, body = '', headers = {}, auth = None, verbose = False, https_proxy = os.getenv('https_proxy', None)):
    url = urlparse(urlString)
    if url.scheme == 'http':
        conn = httplib.HTTPConnection(url.netloc)
    else:
        if httpRequestProps['secure'] or not hasattr(ssl, '_create_unverified_context'):
            conn = httplib.HTTPSConnection(url.netloc if https_proxy is None else https_proxy)
        else:
            conn = httplib.HTTPSConnection(url.netloc if https_proxy is None else https_proxy, context=ssl._create_unverified_context())
        if https_proxy:
            conn.set_tunnel(url.netloc)

    if auth is not None:
        auth = base64.encodestring(auth).replace('\n', '')
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
        except httplib.IncompleteRead as e:
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
    except Exception as e:
        res = dict2obj({ 'status' : 500, 'error': str(e) })
        return res


def getPrettyJson(obj):
    return json.dumps(obj, sort_keys=True, indent=4, separators=(',', ': '))


# class to convert dictionary to objects
class dict2obj(dict):
    def __getattr__(self, name):
        if name in self:
            return self[name]
        else:
            raise AttributeError('object has no attribute "%s"' % name)

    def __setattr__(self, name, value):
        self[name] = value

    def __delattr__(self, name):
        if name in self:
            del self[name]
        else:
            raise AttributeError('object has no attribute "%s"' % name)
