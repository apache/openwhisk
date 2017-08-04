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

package whisk

import (
    "fmt"
    "runtime"
    "strings"
    "os"
)

type DebugLevel string
const (
    DbgInfo    DebugLevel = "Inf"
    DbgWarn    DebugLevel = "Wrn"
    DbgError   DebugLevel = "Err"
    DbgFatal   DebugLevel = "Ftl"
)

const MaxNameLen int = 25

var isVerbose bool
var isDebug bool

func init() {
    if len(os.Getenv("WSK_CLI_DEBUG")) > 0 {    // Useful for tracing init() code, before parms are parsed
        SetDebug(true)
    }
}

func SetDebug(b bool) {
    isDebug = b
}

func SetVerbose (b bool) {
    isVerbose = b
}

func IsVerbose() bool {
    return isVerbose || isDebug
}
func IsDebug() bool {
    return isDebug
}

/* Function for tracing debug level messages to stdout
   Output format:
   [file-or-function-name]:line-#:[DebugLevel] The formated message without any appended \n
 */
func Debug(dl DebugLevel, msgFormat string, args ...interface{}) {
    if isDebug {
        pc, file, line, _ := runtime.Caller(1)
        fcn := runtime.FuncForPC(pc)
        msg := fmt.Sprintf(msgFormat, args...)
        fcnName := fcn.Name()

        // Cobra command Run/RunE functions are anonymous, so the function name is unfriendly,
        // so use a file name instead
        if strings.Contains(fcnName, "commands.glob.") || strings.Contains(fcnName, "whisk.glob.") {
            fcnName = file
        }

        // Only interesting the the trailing function/file name characters
        if len(fcnName) > MaxNameLen {
            fcnName = fcnName[len(fcnName)-MaxNameLen:]
        }
        fmt.Printf("[%-25s]:%03d:[%3s] %v", fcnName, line, dl, msg)
    }
}

/* Function for tracing debug level messages to stdout
   Output format:
   [file-or-function-name]:line-#:[DebugLevel] The formated message without any appended \n
 */
func Verbose(msgFormat string, args ...interface{}) {
    if IsVerbose() {
        msg := fmt.Sprintf(msgFormat, args...)
        fmt.Printf("%v", msg)
    }
}
