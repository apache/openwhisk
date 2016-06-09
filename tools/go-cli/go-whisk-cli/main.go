/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

    "../go-whisk/whisk"
    "../go-whisk-cli/commands"
)

var cliDebug = os.Getenv("WSK_CLI_DEBUG")  // Useful for tracing init() code

func init() {
    if len(cliDebug) > 0 {
        whisk.SetDebug(true)
    }
}

func main() {
    var exitCode int = 0
    var displayUsage bool = false
    var displayMsg bool = false
    var msgDisplayed bool = true

    defer func() {
        if r := recover(); r != nil {
            fmt.Println(r)
            fmt.Println("Application exited unexpectedly")
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
            exitCode = werr.ExitCode
        } else {
            whisk.Debug(whisk.DbgError, "Got some other error - %s\n", err)
            fmt.Printf("%s\n", err)
            displayUsage = false   // Cobra already displayed the usage message
            exitCode = 1;
        }

        // If the err msg should be displayed to the console and it has not already been
        // displayed, display it now.
        if displayMsg && !msgDisplayed {
            fmt.Println(err)
        }

        if displayUsage {
            fmt.Printf("Run '%v --help' for usage.\n", commands.WskCmd.CommandPath())
        } // Displays usage
    }
    os.Exit(exitCode)
    return
}
