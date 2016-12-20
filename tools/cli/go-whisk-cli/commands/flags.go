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

package commands

import (
    "os"
)

///////////
// Flags //
///////////

var cliDebug = os.Getenv("WSK_CLI_DEBUG")  // Useful for tracing init() code

var flags struct {

    global struct {
        verbose     bool
        debug       bool
        auth        string
        apihost     string
        apiversion  string
        insecure    bool
    }

    common struct {
        blocking    bool
        annotation  []string
        annotFile   string
        param       []string
        paramFile   string
        shared      string // AKA "public" or "publish"
        skip        int  // skip first N records
        limit       int  // return max N records
        full        bool // return full records (docs=true for client request)
        summary     bool
        feed        string  // name of feed
        detail      bool
    }

    property struct {
        auth            bool
        apihost         bool
        insecure        bool
        apiversion      bool
        namespace       bool
        cliversion      bool
        apibuild        bool
        apibuildno      bool
        all             bool
        apihostSet      string
        insecureSet     string
        apiversionSet   string
        namespaceSet    string
    }

    action struct {
        docker      bool
        copy        bool
        pipe        bool
        shared      string
        sequence    bool
        timeout     int
        memory      int
        logsize     int
        result      bool
        kind        string
        main        string
    }

    activation struct {
        action          string // retrieve results for this action
        upto            int64  // retrieve results up to certain time
        since           int64  // retrieve results after certain time
        seconds         int    // stop polling for activation upda
        sinceSeconds    int
        sinceMinutes    int
        sinceHours      int
        sinceDays       int
        exit            int
    }

    // rule
    rule struct {
        disable bool
        summary bool
    }

    // trigger
    trigger struct {
        summary bool
    }

    // api
    api struct {
        action     string
        path       string
        verb       string
        basepath   string
        apiname    string
        configfile string
    }
}

func IsVerbose() bool {
    return flags.global.verbose || IsDebug()
}

func IsDebug() bool {
    return len(cliDebug) > 0 || flags.global.debug
}
