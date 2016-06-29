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

package whisk

import (
)
import "fmt"

const EXITCODE_ERR_GENERAL      int = 1
const EXITCODE_ERR_USAGE        int = 2
const EXITCODE_ERR_NETWORK      int = 3
const EXITCODE_ERR_HTTP_RESP    int = 4

const DISPLAY_MSG bool      = true
const NO_DISPLAY_MSG bool   = false
const DISPLAY_USAGE bool    = true
const NO_DISPLAY_USAGE bool = false

type WskError struct {
    RootErr         error
    ExitCode        int
    DisplayMsg      bool    // When true, the error message should be displayed to console
    MsgDisplayed    bool    // When true, the error message has already been displayed, don't display it again
    DisplayUsage    bool    // When true, the CLI usage should be displayed before exiting
}

func (e WskError) Error() string {
    return fmt.Sprintf("error: %s", e.RootErr.Error())
}

// Instantiate a WskError structure
// Parameters:
//  error   - RootErr. object implementing the error interface
//  int     - ExitCode.  Used if error object does not have an exit code OR if ExitCodeOverride is true
//  bool    - DisplayMsg.  If true, the error message should be displayed on the console
//  bool    - DisplayUsage.  If true, the command usage syntax/help should be displayed on the console
//  bool    - MsgDisplay.  If true, the error message has been displayed on the console
func MakeWskError (e error, ec int, flags ...bool ) (we *WskError) {
    we = &WskError{RootErr: e, ExitCode: ec, DisplayMsg: false, DisplayUsage: false, MsgDisplayed: false}
    if len(flags) > 0 { we.DisplayMsg = flags[0] }
    if len(flags) > 1 { we.DisplayUsage = flags[1] }
    if len(flags) > 2 { we.MsgDisplayed = flags[2] }
    return we
}

// Instantiate a WskError structure
// Parameters:
//  error   - RootErr. object implementing the error interface
//  WskError -WskError being wrappered.  It's exitcode will be used as this WskError's exitcode.  Ignored if nil
//  int     - ExitCode.  Used if error object is nil or if the error object is not a WskError
//  bool    - DisplayMsg.  If true, the error message should be displayed on the console
//  bool    - DisplayUsage.  If true, the command usage syntax/help should be displayed on the console
//  bool    - MsgDisplay.  If true, the error message has been displayed on the console
func MakeWskErrorFromWskError (e error, werr error, ec int, flags ...bool ) (we *WskError) {
    var exitCode int = ec

    // Pull exit code from wsk error if it exists
    if werr !=nil {
        // werr can either be a pointer reference
        if we, ok := werr.(*WskError); ok {
            exitCode = we.ExitCode
            flags[1] = we.DisplayUsage || flags[1]  // Display usage syntax if either error requests it
        }

        // ... or a value
        if we, ok := werr.(WskError); ok {
            exitCode = we.ExitCode
            flags[1] = we.DisplayUsage || flags[1]  // Display usage syntax if either error requests it
        }
    }

    return MakeWskError(e, exitCode, flags...)
}

