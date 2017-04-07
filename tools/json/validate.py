#!/usr/bin/python
"""Validate that a string conforms to a json schema.

  usage: validate.py obj schema
  where obj and schema are both strings.
  prints 'true' if validate succeeds, 'false' otherwise

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
import sys
import json
from jsonschema import validate


if len(sys.argv) != 3:
    print('usage: validate.py obj schema')
    sys.exit(-1)

a1 = sys.argv[1].replace('\n', '')
a2 = sys.argv[2].replace('\n', '')
obj = json.loads(a1)
schema = json.loads(a2)

try:
    validate(obj, schema)
    print('true')
except:
    print('false')
