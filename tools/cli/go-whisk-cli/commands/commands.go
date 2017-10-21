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

package commands

import (
    "errors"
    "net/http"
    "os"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/spf13/cobra"
)

var Client *whisk.Client
const DefaultOpenWhiskApiPath string = "/api"
var UserAgent string = "OpenWhisk-CLI"

func SetupClientConfig(cmd *cobra.Command, args []string) (error){
    baseURL, err := whisk.GetURLBase(Properties.APIHost, DefaultOpenWhiskApiPath)

    // Determine if the parent command will require the API host to be set
    apiHostRequired := (cmd.Parent().Name() == "property" && cmd.Name() == "get" && (flags.property.auth ||
      flags.property.cert || flags.property.key || flags.property.apihost || flags.property.namespace ||
      flags.property.apiversion || flags.property.cliversion)) ||
      (cmd.Parent().Name() == "property" && cmd.Name() == "set" && (len(flags.property.apihostSet) > 0 ||
        len(flags.property.apiversionSet) > 0 || len(flags.global.auth) > 0)) ||
      (cmd.Parent().Name() == "sdk" && cmd.Name() == "install" && len(args) > 0 && args[0] == "bashauto")

    // Display an error if the parent command requires an API host to be set, and the current API host is not valid
    if err != nil && !apiHostRequired {
        whisk.Debug(whisk.DbgError, "whisk.GetURLBase(%s, %s) error: %s\n", Properties.APIHost, DefaultOpenWhiskApiPath, err)
        errMsg := wski18n.T("The API host is not valid: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return whiskErr
    }

    clientConfig := &whisk.Config{
        Cert: Properties.Cert,
        Key: Properties.Key,
        AuthToken:  Properties.Auth,
        Namespace:  Properties.Namespace,
        BaseURL:    baseURL,
        Version:    Properties.APIVersion,
        Insecure:   flags.global.insecure,
        Host:       Properties.APIHost,
        UserAgent:  UserAgent + "/1.0 (" + Properties.CLIVersion + ")",
    }

    // Setup client
    Client, err = whisk.NewClient(http.DefaultClient, clientConfig)

    if err != nil {
        whisk.Debug(whisk.DbgError, "whisk.NewClient(%#v, %#v) error: %s\n", http.DefaultClient, clientConfig, err)
        errMsg := wski18n.T("Unable to initialize server connection: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return nil
}

func init() {}

func getKeyValueArgs(args []string, argIndex int, parsedArgs []string) ([]string, []string, error) {
    var whiskErr error
    var key string
    var value string

    if len(args) - 1 >= argIndex + 2 {
        key = args[argIndex + 1]
        value = args[argIndex + 2]
        parsedArgs = append(parsedArgs, getFormattedJSON(key, value))
        args = append(args[:argIndex], args[argIndex + 3:]...)
    } else {
        whisk.Debug(whisk.DbgError, "Arguments for '%s' must be a key/value pair; args: %s", args[argIndex], args)
        errMsg := wski18n.T("Arguments for '{{.arg}}' must be a key/value pair",
            map[string]interface{}{"arg": args[argIndex]})
        whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
            whisk.DISPLAY_USAGE)
    }

    return parsedArgs, args, whiskErr
}

func getValueFromArgs(args []string, argIndex int, parsedArgs []string) ([]string, []string, error) {
    var whiskErr error

    if len(args) - 1 >= argIndex + 1 {
        parsedArgs = append(parsedArgs, args[argIndex + 1])
        args = append(args[:argIndex], args[argIndex + 2:]...)
    } else {
        whisk.Debug(whisk.DbgError, "An argument must be provided for '%s'; args: %s", args[argIndex], args)
        errMsg := wski18n.T("An argument must be provided for '{{.arg}}'",
            map[string]interface{}{"arg": args[argIndex]})
        whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
            whisk.DISPLAY_USAGE)
    }

    return parsedArgs, args, whiskErr
}

func parseArgs(args []string) ([]string, []string, []string, error) {
    var paramArgs []string
    var annotArgs []string
    var whiskErr error

    i := 0

    for i < len(args) {
        if args[i] == "-P" || args[i] == "--param-file" {
            paramArgs, args, whiskErr = getValueFromArgs(args, i, paramArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getValueFromArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The parameter arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)
                return nil, nil, nil, whiskErr
            }

            filename := paramArgs[len(paramArgs) - 1]
            paramArgs[len(paramArgs) - 1], whiskErr = readFile(filename)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "readFile(%s) error: %s\n", filename, whiskErr)
                return nil, nil, nil, whiskErr
            }
        } else if args[i] == "-A" || args[i] == "--annotation-file" {
            annotArgs, args, whiskErr = getValueFromArgs(args, i, annotArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getValueFromArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The annotation arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)
                return nil, nil, nil, whiskErr
            }

            filename := annotArgs[len(annotArgs) - 1]
            annotArgs[len(annotArgs) - 1], whiskErr = readFile(filename)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "readFile(%s) error: %s\n", filename, whiskErr)
                return nil, nil, nil, whiskErr
            }
        } else if args[i] == "-p" || args[i] == "--param" {
            paramArgs, args, whiskErr = getKeyValueArgs(args, i, paramArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getKeyValueArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The parameter arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)
                return nil, nil, nil, whiskErr
            }
        } else if args[i] == "-a" || args[i] == "--annotation"{
            annotArgs, args, whiskErr = getKeyValueArgs(args, i, annotArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getKeyValueArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The annotation arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)
                return nil, nil, nil, whiskErr
            }
        } else {
            i++
        }
    }

    whisk.Debug(whisk.DbgInfo, "Found param args '%s'.\n", paramArgs)
    whisk.Debug(whisk.DbgInfo, "Found annotations args '%s'.\n", annotArgs)
    whisk.Debug(whisk.DbgInfo, "Arguments with param args removed '%s'.\n", args)

    return args, paramArgs, annotArgs, nil
}

func Execute() error {
    var err error

    whisk.Debug(whisk.DbgInfo, "wsk args: %#v\n", os.Args)
    os.Args, flags.common.param, flags.common.annotation, err = parseArgs(os.Args)

    if err != nil {
        whisk.Debug(whisk.DbgError, "parseParams(%s) failed: %s\n", os.Args, err)
        errMsg := wski18n.T("Failed to parse arguments: {{.err}}", map[string]interface{}{"err":err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    err = loadProperties()
    if err != nil {
        whisk.Debug(whisk.DbgError, "loadProperties() error: %s\n", err)
        errMsg := wski18n.T("Unable to access configuration properties: {{.err}}", map[string]interface{}{"err":err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return WskCmd.Execute()
}
