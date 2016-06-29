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
    "fmt"
    "net/http"
    "net/url"
    "os"

    "../../go-whisk/whisk"
    "errors"
    "github.com/spf13/cobra"
)

var client *whisk.Client

func setupClientConfig(cmd *cobra.Command, args []string) (error){
    var apiHostBaseUrl = fmt.Sprintf("https://%s/api/", Properties.APIHost)

    baseURL, err := url.Parse(apiHostBaseUrl)

    if err != nil {
        whisk.Debug(whisk.DbgError, "url.Parse(%s) error: %s\n", apiHostBaseUrl, err)
        errMsg := fmt.Sprintf("Invalid apiHost value '%s' : %s", Properties.APIHost, err)
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return whiskErr
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
        errMsg := fmt.Sprintf("Unable to initialize server connection: %s", err)
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

func parseArgs(args []string) ([]string, []string, []string, error) {
    var paramArgs []string
    var annotArgs []string
    var whiskErr error
    var errMsg string
    var parsingParam bool
    var parsingAnnot bool

    i := 0

    for i < len(args) {

        if !parsingAnnot && args[i] == "-p" || args[i] == "--param" {
            parsingParam = true

            if len(args) - 1 >= i + 2 {
                parsingParam = false
                paramArgs = append(paramArgs, args[i + 1])
                paramArgs = append(paramArgs, args[i + 2])
                args = append(args[:i], args[i + 3:]...)
            } else if len(args) - 1 >= i + 1 {
                parsingParam = false
                paramArgs = append(paramArgs, args[i + 1])
                paramArgs = append(paramArgs, "")
                args = append(args[:i], args[i + 2:]...)
            } else {
                whisk.Debug(whisk.DbgError, "Parameter arguments must be a key value pair; args: %s", args)

                errMsg = fmt.Sprintf("Parameter arguments must be a key value pair: %s", args)
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)

                return nil, nil, nil, whiskErr
            }
        } else if !parsingParam && args[i] == "-a" || args[i] == "--annotation"{
            parsingAnnot = true

            if len(args) - 1 >= i + 2 {
                parsingAnnot = false
                annotArgs = append(annotArgs, args[i + 1])
                annotArgs = append(annotArgs, args[i + 2])
                args = append(args[:i], args[i + 3:]...)
            } else if len(args) - 1 >= i + 1 {
                parsingAnnot = false
                annotArgs = append(annotArgs, args[i + 1])
                annotArgs = append(annotArgs, "")
                args = append(args[:i], args[i + 2:]...)
            } else {
                whisk.Debug(whisk.DbgError, "Parameter arguments must be a key value pair; args: %s", args)

                errMsg = fmt.Sprintf("Parameter arguments must be a key value pair: %s", args)
                whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                    whisk.DISPLAY_USAGE)

                return nil, nil, nil, whiskErr
            }
        } else {
            i++
        }
    }

    whisk.Debug(whisk.DbgInfo, "Found param args %s.\n", paramArgs)
    whisk.Debug(whisk.DbgInfo, "Found annotations args %s.\n", annotArgs)
    whisk.Debug(whisk.DbgInfo, "Arguments with param args removed %s.\n", args)

    return args, paramArgs, annotArgs, nil
}

func Execute() error {
    var err error

    os.Args, flags.common.param, flags.common.annotation, err = parseArgs(os.Args)

    if err != nil {
        whisk.Debug(whisk.DbgError, "parseParams(%s) failed: %s\n", os.Args, err)
        errMsg := fmt.Sprintf("Failed to parse arguments: %s", err)
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return WskCmd.Execute()
}
