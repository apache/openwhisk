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

package openwhisk.java.action;

import java.security.Permission;

/**
 * A `SecurityManager` installed when executing action code. The purpose here
 * is not so much to prevent malicious behavior than it is to prevent users from
 * shooting themselves in the foot. In particular, anything that kills the JVM
 * will result in unhelpful action error messages.
 */
public class WhiskSecurityManager extends SecurityManager {
    @Override
    public void checkPermission(Permission p) {
        // Not throwing means accepting anything.
    }

    @Override
    public void checkPermission(Permission p, Object ctx) {
        // Not throwing means accepting anything.
    }

    @Override
    public void checkExit(int status) {
        super.checkExit(status);
        throw new SecurityException("System.exit(" + status + ") called from within an action.");
    }
}
