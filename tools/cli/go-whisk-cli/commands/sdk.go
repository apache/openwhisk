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
    "fmt"
    "io"
    "os"
    "strings"

    "github.com/spf13/cobra"

    "../../go-whisk/whisk"
    "../wski18n"
)

// sdkCmd represents the sdk command
var sdkCmd = &cobra.Command{
    Use:   "sdk",
    Short: wski18n.T("work with the sdk"),
}

type sdkInfo struct {
    UrlPath   string
    FileName  string
    isGzTar   bool
    IsGzip    bool
    IsZip     bool
    IsTar     bool
    Unpack    bool
    UnpackDir string
}

var sdkMap map[string]*sdkInfo
const SDK_DOCKER_COMPONENT_NAME string = "docker"
const SDK_IOS_COMPONENT_NAME string = "ios"
const BASH_AUTOCOMPLETE_FILENAME string = "wsk_cli_bash_completion.sh"

var sdkInstallCmd = &cobra.Command{
    Use:   "install COMPONENT",
    Short: wski18n.T("install SDK artifacts"),
    Long: wski18n.T("install SDK artifacts, where valid COMPONENT values are docker, ios, and bashauto"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := wski18n.T("The SDK component argument is missing. One component (docker, ios, or bashauto) must be specified")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        component := strings.ToLower(args[0])
        switch component {
        case "docker":
            err = dockerInstall()
        case "ios":
            err = iOSInstall()
        case "bashauto":
            if flags.sdk.stdout {
                if err = WskCmd.GenBashCompletion(os.Stdout); err != nil {
                    whisk.Debug(whisk.DbgError, "GenBashCompletion error: %s\n", err)
                    errStr := wski18n.T("Unable to output bash command completion {{.err}}",
                            map[string]interface{}{"err": err})
                    werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                    return werr
                }
             } else {
                    err = WskCmd.GenBashCompletionFile(BASH_AUTOCOMPLETE_FILENAME)
                    if (err != nil) {
                        whisk.Debug(whisk.DbgError, "GenBashCompletionFile('%s`) error: \n", BASH_AUTOCOMPLETE_FILENAME, err)
                        errStr := wski18n.T("Unable to generate '{{.name}}': {{.err}}",
                                map[string]interface{}{"name": BASH_AUTOCOMPLETE_FILENAME, "err": err})
                        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                        return werr
                    }
                    fmt.Printf(
                        wski18n.T("bash_completion_msg",
                    map[string]interface{}{"name": BASH_AUTOCOMPLETE_FILENAME}))
             }
        default:
            whisk.Debug(whisk.DbgError, "Invalid component argument '%s'\n", component)
            errStr := wski18n.T("The SDK component argument '{{.component}}' is invalid. Valid components are docker, ios and bashauto",
                    map[string]interface{}{"component": component})
            err = whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        }

        if err != nil {
            return err
        }
        return nil
    },
}

func dockerInstall() error {
    var err error

    targetFile := sdkMap[SDK_DOCKER_COMPONENT_NAME].FileName
    if _, err = os.Stat(targetFile); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", targetFile)
        errStr := wski18n.T("The file '{{.name}}' already exists.  Delete it and retry.",
            map[string]interface{}{"name": targetFile})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if err = sdkInstall(SDK_DOCKER_COMPONENT_NAME); err != nil {
        whisk.Debug(whisk.DbgError, "sdkInstall(%s) failed: %s\n", SDK_DOCKER_COMPONENT_NAME, err)
        errStr := wski18n.T("The {{.component}} SDK installation failed: {{.err}}",
                map[string]interface{}{"component": SDK_DOCKER_COMPONENT_NAME, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    fmt.Println(wski18n.T("The docker skeleton is now installed at the current directory."))
    return nil
}

func iOSInstall() error {
    var err error

    if err = sdkInstall(SDK_IOS_COMPONENT_NAME); err != nil {
        whisk.Debug(whisk.DbgError, "sdkInstall(%s) failed: %s\n", SDK_IOS_COMPONENT_NAME, err)
        errStr := wski18n.T("The {{.component}} SDK installation failed: {{.err}}",
                map[string]interface{}{"component": SDK_IOS_COMPONENT_NAME, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    fmt.Printf(
        wski18n.T("Downloaded OpenWhisk iOS starter app. Unzip '{{.name}}' and open the project in Xcode.\n",
            map[string]interface{}{"name": sdkMap[SDK_IOS_COMPONENT_NAME].FileName}))
    return nil
}

func sdkInstall(componentName string) error {
    targetFile := sdkMap[componentName].FileName
    if _, err := os.Stat(targetFile); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", targetFile)
        errStr := wski18n.T("The file '{{.name}}' already exists.  Delete it and retry.",
                map[string]interface{}{"name": targetFile})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    resp, err := Client.Sdks.Install(sdkMap[componentName].UrlPath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Client.Sdks.Install(%s) failed: %s\n", sdkMap[componentName].UrlPath, err)
        errStr := wski18n.T("Unable to retrieve '{{.urlpath}}' SDK: {{.err}}",
                map[string]interface{}{"urlpath": sdkMap[componentName].UrlPath, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if resp.Body == nil {
        whisk.Debug(whisk.DbgError, "SDK Install HTTP response has no body\n")
        errStr := wski18n.T("Server failed to send the '{{.component}}' SDK: {{.err}}",
                map[string]interface{}{"name": componentName, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Create the SDK file
    sdkfile, err := os.Create(targetFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failure: %s\n", targetFile, err)
        errStr := wski18n.T("Error creating SDK file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": targetFile, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Read the HTTP response body and write it to the SDK file
    whisk.Debug(whisk.DbgInfo, "Reading SDK file from HTTP response body\n")
    _, err = io.Copy(sdkfile, resp.Body)
    if err != nil {
        whisk.Debug(whisk.DbgError, "io.Copy() of resp.Body into sdkfile failure: %s\n", err)
        errStr := wski18n.T("Error copying server response into file: {{.err}}",
                map[string]interface{}{"err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        sdkfile.Close()
        return werr

    }
    sdkfile.Close()     // Don't use 'defer' since this file might need to be deleted after unpack

    // At this point, the entire file is downloaded from the server
    // Check if there is any special post-download processing (i.e. unpack)
    if sdkMap[componentName].Unpack {
        // Make sure the target directory does not already exist
        defer os.Remove(targetFile)
        targetdir := sdkMap[componentName].UnpackDir
        if _, err = os.Stat(targetdir); err == nil {
            whisk.Debug(whisk.DbgError, "os.Stat reports that directory '%s' exists\n", targetdir)
            errStr := wski18n.T("The directory '{{.name}}' already exists.  Delete it and retry.",
                    map[string]interface{}{"name": targetdir})
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // If the packed SDK is a .tgz file, unpack it in two steps
        // 1. UnGzip into temp .tar file
        // 2. Untar the contents into the current folder
        if sdkMap[componentName].isGzTar {
            whisk.Debug(whisk.DbgInfo, "unGzipping downloaded file\n")
            err := unpackGzip(targetFile, "temp.tar")
            if err != nil {
                whisk.Debug(whisk.DbgError, "unpackGzip(%s,temp.tar) failure: %s\n", targetFile, err)
                errStr := wski18n.T("Error unGzipping file '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": targetFile, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            defer os.Remove("temp.tar")

            whisk.Debug(whisk.DbgInfo, "unTarring unGzipped file\n")
            err = unpackTar("temp.tar")
            if err != nil {
                whisk.Debug(whisk.DbgError, "unpackTar(temp.tar) failure: %s\n", err)
                errStr := wski18n.T("Error untarring file '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": "temp.tar", "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        // Future SDKs may require other unpacking procedures not yet covered here....
    }

    return nil
}

func init() {
    sdkInstallCmd.Flags().BoolVarP(&flags.sdk.stdout, "stdout", "s", false, wski18n.T("prints bash command completion script to stdout"))

    sdkCmd.AddCommand(sdkInstallCmd)

    sdkMap = make(map[string]*sdkInfo)
    sdkMap["docker"] = &sdkInfo{ UrlPath: "blackbox.tar.gz", FileName: "blackbox.tar.gz", isGzTar: true, Unpack: true, UnpackDir: "dockerSkeleton"}
    sdkMap["ios"] = &sdkInfo{ UrlPath: "OpenWhiskIOSStarterApp.zip", FileName: "OpenWhiskIOSStarterApp.zip", IsZip: true, Unpack: false}
}
