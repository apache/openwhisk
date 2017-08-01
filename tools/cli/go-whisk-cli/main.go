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

package main

import (
    "fmt"
    "os"
    "reflect"
    goi18n "github.com/nicksnyder/go-i18n/i18n"
    "github.com/fatih/color"

    "../go-whisk/whisk"
    "./commands"
    "./wski18n"
    "github.com/mattn/go-colorable"
)

// CLI_BUILD_TIME holds the time of the CLI build.  During gradle builds,
// this value will be overwritten via the command:
//     go build -ldflags "-X main.CLI_BUILD_TIME=nnnnn"   // nnnnn is the new timestamp
var CLI_BUILD_TIME string = "not set"

var cliDebug = os.Getenv("WSK_CLI_DEBUG")  // Useful for tracing init() code

var T goi18n.TranslateFunc

func init() {
    if len(cliDebug) > 0 {
        whisk.SetDebug(true)
    }

    T = wski18n.T

    // Rest of CLI uses the Properties struct, so set the build time there
    commands.Properties.CLIVersion = CLI_BUILD_TIME
}

func main() {
    var exitCode int = 0
    var displayUsage bool = false
    var displayMsg bool = false
    var msgDisplayed bool = true
    var displayPrefix bool = true

    defer func() {
        if r := recover(); r != nil {
            fmt.Println(r)
            fmt.Println(T("Application exited unexpectedly"))
        }
    }()

    if err := commands.Execute(); err != nil {
        whisk.Debug(whisk.DbgInfo, "err object type: %s\n", reflect.TypeOf(err).String())

        werr, isWskError := err.(*whisk.WskError)  // Is the err a WskError?
        if isWskError {
            whisk.Debug(whisk.DbgError, "Got a *whisk.WskError error: %#v\n", werr)
            displayUsage = werr.DisplayUsage
            displayMsg = werr.DisplayMsg
            msgDisplayed = werr.MsgDisplayed
            displayPrefix = werr.DisplayPrefix
            exitCode = werr.ExitCode
        } else {
            whisk.Debug(whisk.DbgError, "Got some other error: %s\n", err)
            fmt.Fprintf(os.Stderr, "%s\n", err)

            displayUsage = false   // Cobra already displayed the usage message
            exitCode = 1;
        }

        outputStream := colorable.NewColorableStderr()

        // If the err msg should be displayed to the console and it has not already been
        // displayed, display it now.
        if displayMsg && !msgDisplayed && displayPrefix && exitCode != 0 {
            fmt.Fprintf(outputStream, "%s%s\n", color.RedString(T("error: ")), err)
        } else if displayMsg && !msgDisplayed && !displayPrefix && exitCode != 0 {
            fmt.Fprintf(outputStream, "%s\n", err)
        } else if displayMsg && !msgDisplayed && exitCode == 0 {
            fmt.Fprintf(outputStream, "%s\n", err)
        }

        // Displays usage
        if displayUsage {
            fmt.Fprintf(outputStream, T("Run '{{.Name}} --help' for usage.\n",
                map[string]interface{}{ "Name" : commands.WskCmd.CommandPath()}))
        }
    }
    os.Exit(exitCode)
    return
}
