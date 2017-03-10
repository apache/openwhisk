"""Python bad action runner (sleep forever).

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
import time
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner


class Runner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self)

    def init(self, message):
        if 'code' in message and message['code'] == 'sleep':
            # sleep forever/never respond
            while True:
                print("sleeping")
                time.sleep(60)
        elif 'code' in message and message['code'] == 'exit':
            print("exiting")
            sys.exit(1)
        else:
            return ActionRunner.init(self, message)

    def run(self, args, env):
        if 'sleep' in args:
            # sleep forever/never respond
            while True:
                print("sleeping")
                time.sleep(60)
        elif 'exit' in args:
            print("exiting")
            sys.exit(1)
        else:
            return ActionRunner.run(self, args, env)

if __name__ == "__main__":
    setRunner(Runner())
    main()
