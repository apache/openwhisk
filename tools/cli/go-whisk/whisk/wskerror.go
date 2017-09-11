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

const EXIT_CODE_ERR_GENERAL     int = 1
const EXIT_CODE_ERR_USAGE       int = 2
const EXIT_CODE_ERR_NETWORK     int = 3
const EXIT_CODE_ERR_HTTP_RESP   int = 4
const NOT_ALLOWED               int = 149
const EXIT_CODE_TIMED_OUT       int = 202
const EXIT_CODE_NOT_FOUND       int = 148

const DISPLAY_MSG               bool = true
const NO_DISPLAY_MSG            bool = false
const DISPLAY_USAGE             bool = true
const NO_DISPLAY_USAGE          bool = false
const NO_MSG_DISPLAYED          bool = false
const DISPLAY_PREFIX            bool = true
const NO_DISPLAY_PREFIX         bool = false
const APPLICATION_ERR           bool = true
const NO_APPLICATION_ERR        bool = false
const TIMED_OUT                 bool = true

type WskError struct {
    RootErr             error   // Parent error
    ExitCode            int     // Error code to be returned to the OS
    DisplayMsg          bool    // When true, the error message should be displayed to console
    MsgDisplayed        bool    // When true, the error message has already been displayed, don't display it again
    DisplayUsage        bool    // When true, the CLI usage should be displayed before exiting
    DisplayPrefix       bool    // When true, the CLI will prefix an error message with "error: "
    ApplicationError    bool    // When true, the error is a result of an application failure
    TimedOut            bool    // When True, the error is a result of a timeout
}

/*
Prints the error message contained inside an WskError. An error prefix may, or may not be displayed depending on the
WskError's setting for DisplayPrefix.

Parameters:
    err     - WskError object used to display an error message from
 */
func (whiskError WskError) Error() string {
    return whiskError.RootErr.Error()
}

/*
Instantiate a WskError structure
Parameters:
    error   - RootErr. object implementing the error interface
    int     - ExitCode.  Used if error object does not have an exit code OR if ExitCodeOverride is true
    bool    - DisplayMsg.  If true, the error message should be displayed on the console
    bool    - DisplayUsage.  If true, the command usage syntax/help should be displayed on the console
    bool    - MsgDisplayed.  If true, the error message has been displayed on the console
    bool    - DisplayPreview.  If true, the error message will be prefixed with "error: "
    bool    - TimedOut. If true, the error is a result of a timeout
*/
func MakeWskError (err error, exitCode int, flags ...bool ) (resWhiskError *WskError) {
    resWhiskError = &WskError{
        RootErr: err,
        ExitCode: exitCode,
        DisplayMsg: false,
        DisplayUsage: false,
        MsgDisplayed: false,
        DisplayPrefix: true,
        ApplicationError: false,
        TimedOut: false,
    }

    if len(flags) > 0 { resWhiskError.DisplayMsg = flags[0] }
    if len(flags) > 1 { resWhiskError.DisplayUsage = flags[1] }
    if len(flags) > 2 { resWhiskError.MsgDisplayed = flags[2] }
    if len(flags) > 3 { resWhiskError.DisplayPrefix = flags[3] }
    if len(flags) > 4 { resWhiskError.ApplicationError = flags[4] }
    if len(flags) > 5 { resWhiskError.TimedOut = flags[5] }

    return resWhiskError
}

/*
Instantiate a WskError structure
Parameters:
    error       - RootErr. object implementing the error interface
    WskError    - WskError being wrappered.  It's exitcode will be used as this WskError's exitcode.  Ignored if nil
    int         - ExitCode. Used if error object is nil or if the error object is not a WskError
    bool        - DisplayMsg. If true, the error message should be displayed on the console
    bool        - DisplayUsage. If true, the command usage syntax/help should be displayed on the console
    bool        - MsgDisplayed. If true, the error message has been displayed on the console
    bool        - ApplicationError. If true, the error is a result of an application error
    bool        - TimedOut. If true, the error resulted from a timeout
*/
func MakeWskErrorFromWskError (baseError error, whiskError error, exitCode int, flags ...bool) (resWhiskError *WskError) {

    // Get the exit code, and flags from the existing Whisk error
    if whiskError != nil {

        // Ensure the Whisk error is a pointer
        switch errorType := whiskError.(type) {
            case *WskError:
                resWhiskError = errorType
            case WskError:
                resWhiskError = &errorType
        }

        if resWhiskError != nil {
            exitCode, flags = getWhiskErrorProperties(resWhiskError, flags...)
        }
    }

    return MakeWskError(baseError, exitCode, flags...)
}

/*
Returns the settings from a WskError. Values returned will include ExitCode, DisplayMsg, DisplayUsage, MsgDisplayed,
DisplayPrefix, TimedOut.

Parameters:
    whiskError  - WskError to examine.
    flags       - Boolean values that may override the WskError object's values for DisplayMsg, DisplayUsage,
                    MsgDisplayed, ApplicationError, TimedOut.
 */
func getWhiskErrorProperties(whiskError *WskError, flags ...bool) (int, []bool) {
    if len(flags) > 0 {
        flags[0] = whiskError.DisplayMsg
    } else {
        flags = append(flags, whiskError.DisplayMsg)
    }

    if len(flags) > 1 {
        flags[1] = whiskError.DisplayUsage || flags[1]
    } else {
        flags = append(flags, whiskError.DisplayUsage)
    }

    if len(flags) > 2 {
        flags[2] = whiskError.MsgDisplayed || flags[2]
    } else {
        flags = append(flags, whiskError.MsgDisplayed)
    }

    if len(flags) > 3 {
        flags[3] = whiskError.DisplayPrefix || flags[3]
    } else {
        flags = append(flags, whiskError.DisplayPrefix)
    }

    if len(flags) > 4 {
        flags[4] = whiskError.ApplicationError || flags[4]
    } else {
        flags = append(flags, whiskError.ApplicationError)
    }

    if len(flags) > 5 {
        flags[5] = whiskError.TimedOut || flags[5]
    } else {
        flags = append(flags, whiskError.TimedOut)
    }

    return whiskError.ExitCode, flags
}
