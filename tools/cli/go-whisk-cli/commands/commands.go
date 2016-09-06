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
    "errors"
    "fmt"
    "net/http"
    "net/url"
    "os"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/spf13/cobra"
)

var client *whisk.Client

func setupClientConfig(cmd *cobra.Command, args []string) (error){
    baseURL, err := urlBase()

    // Don't display the 'invalid apihost' error if this CLI invocation is setting that value
    isApiHostPropSetCmd := cmd.Parent().Name() == "property" && cmd.Name() == "set" && len(flags.property.apihostSet) > 0
    if err != nil && !isApiHostPropSetCmd {
        whisk.Debug(whisk.DbgError, "urlBase() error: %s\n", err)
        errMsg := fmt.Sprintf(
            wski18n.T("error: The configured API host property value '{{.apihost}}' is invalid : {{.err}}",
                map[string]interface{}{"apihost": Properties.APIHost, "err": err}))
        fmt.Fprintln(os.Stderr, errMsg)
        errMsg = fmt.Sprintf(
            wski18n.T("A default API host value of 'localhost' will be used"))
        fmt.Fprintln(os.Stderr, errMsg)
        errMsg = fmt.Sprintf(
            wski18n.T("Run 'wsk property set --apihost HOST' to set a valid API host value"))
        fmt.Fprintln(os.Stderr, errMsg)

        baseURL, _ = url.Parse("https://localhost/api/")
    }

    clientConfig := &whisk.Config{
        AuthToken:  Properties.Auth,
        Namespace:  Properties.Namespace,
        BaseURL:    baseURL,
        Version:    Properties.APIVersion,
        Insecure:   flags.global.insecure,
    }

    // Setup client
    client, err = whisk.NewClient(http.DefaultClient, clientConfig)

    if err != nil {
        whisk.Debug(whisk.DbgError, "whisk.NewClient(%#v, %#v) error: %s\n", http.DefaultClient, clientConfig, err)
        errMsg := fmt.Sprintf(
            wski18n.T("Unable to initialize server connection: {{.err}}", map[string]interface{}{"err": err}))
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return nil
}

func init() {
    var err error

    err = loadProperties()
    if err != nil {
        whisk.Debug(whisk.DbgError, "loadProperties() error: %s\n", err)
        fmt.Println(err)
        os.Exit(whisk.EXITCODE_ERR_GENERAL)
    }
}

func getKeyValueArgs(args []string, argIndex int, parsedArgs []string) ([]string, []string, error) {
    var whiskErr error

    if len(args) - 1 >= argIndex + 2 {
        parsedArgs = append(parsedArgs, args[argIndex + 1])
        parsedArgs = append(parsedArgs, args[argIndex + 2])
        args = append(args[:argIndex], args[argIndex + 3:]...)
    } else {
        whisk.Debug(whisk.DbgError, "Arguments for '%s' must be a key/value pair; args: %s", args[argIndex], args)
        errMsg := wski18n.T("Arguments for '{{.arg}}' must be a key/value pair",
            map[string]interface{}{"arg": args[argIndex]})
        whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
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
        if args[i] == "-p" || args[i] == "--param" {
            paramArgs, args, whiskErr = getKeyValueArgs(args, i, paramArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getKeyValueArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The parameter arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)
                return nil, nil, nil, whiskErr
            }
        } else if args[i] == "-a" || args[i] == "--annotation"{
            annotArgs, args, whiskErr = getKeyValueArgs(args, i, annotArgs)
            if whiskErr != nil {
                whisk.Debug(whisk.DbgError, "getKeyValueArgs(%#v, %d) failed: %s\n", args, i, whiskErr)
                errMsg := wski18n.T("The annotation arguments are invalid: {{.err}}",
                    map[string]interface{}{"err": whiskErr})
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
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
        errMsg := fmt.Sprintf(
            wski18n.T("Failed to parse arguments: {{.err}}", map[string]interface{}{"err":err}))
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return WskCmd.Execute()
}
