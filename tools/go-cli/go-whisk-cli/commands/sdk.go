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
    "io"
    "os"

    "github.com/spf13/cobra"

    "../../go-whisk/whisk"
    "strings"
)

// sdkCmd represents the sdk command
var sdkCmd = &cobra.Command{
    Use:   "sdk",
    Short: "work with the sdk",
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
const SDK_SWIFT_COMPONENT_NAME string = "swift"

var sdkInstallCmd = &cobra.Command{
    Use:   "install <component string:{docker,swift,iOS}>",
    Short: "install artifacts",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("The SDK component argument is invalid.  One component (docker, swift, or ios) must be specified")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        component := strings.ToLower(args[0])
        switch component {
        case "docker":
            err = dockerInstall()
        case "swift":
            err = swiftInstall()
        case "ios":
            err = iOSInstall()
        default:
            whisk.Debug(whisk.DbgError, "Invalid component argument '%s'\n", component)
            errStr := fmt.Sprintf("The SDK component argument '%s' is invalid.  Valid components are docker, swift, and ios", component)
            err = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
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
        errStr := fmt.Sprintf("The file %s already exists.  Delete it and retry.", targetFile)
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if err = sdkInstall(SDK_DOCKER_COMPONENT_NAME); err != nil {
        whisk.Debug(whisk.DbgError, "sdkInstall(%s) failed: %s\n", SDK_DOCKER_COMPONENT_NAME, err)
        errStr := fmt.Sprintf("The %s SDK installation failed: %s", SDK_DOCKER_COMPONENT_NAME, err)
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    fmt.Println("The docker skeleton is now installed at the current directory.")
    return nil
}

func swiftInstall() error {
    fmt.Println("Swift SDK coming soon.")
    return nil
}

func iOSInstall() error {
    var err error

    if err = sdkInstall(SDK_IOS_COMPONENT_NAME); err != nil {
        whisk.Debug(whisk.DbgError, "sdkInstall(%s) failed: %s\n", SDK_IOS_COMPONENT_NAME, err)
        errStr := fmt.Sprintf("The %s SDK installation failed: %s", SDK_IOS_COMPONENT_NAME, err)
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    fmt.Printf("Downloaded OpenWhisk iOS starter app. Unzip %s and open the project in Xcode.\n", sdkMap[SDK_IOS_COMPONENT_NAME].FileName)
    return nil
}

func sdkInstall(componentName string) error {
    targetFile := sdkMap[componentName].FileName
    if _, err := os.Stat(targetFile); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", targetFile)
        errStr := fmt.Sprintf("The file %s already exists.  Delete it and retry.", targetFile)
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    resp, err := client.Sdks.Install(sdkMap[componentName].UrlPath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "client.Sdks.Install(%s) failed: %s\n", sdkMap[componentName].UrlPath, err)
        errStr := fmt.Sprintf("Unable to retrieve '%s' SDK: %s", sdkMap[componentName].UrlPath, err)
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if resp.Body == nil {
        whisk.Debug(whisk.DbgError, "SDK Install HTTP response has no body\n")
        errStr := fmt.Sprintf("Server failed to send the '%s' SDK: %s", componentName, err)
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Create the SDK file
    sdkfile, err := os.Create(targetFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failure: %s\n", targetFile, err)
        errStr := fmt.Sprintf("Error creating SDK file %s: %s", targetFile, err)
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Read the HTTP response body and write it to the SDK file
    whisk.Debug(whisk.DbgInfo, "Reading SDK file from HTTP response body\n")
    _, err = io.Copy(sdkfile, resp.Body)
    if err != nil {
        whisk.Debug(whisk.DbgError, "io.Copy() of resp.Body into sdkfile failure: %s\n", err)
        errStr := fmt.Sprintf("Error copying response body into file: %s", err)
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
            errStr := fmt.Sprintf("The directory %s already exists.  Delete it and retry.", targetdir)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
                errStr := fmt.Sprintf("Error unGziping file %s: %s", targetFile, err)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            defer os.Remove("temp.tar")

            whisk.Debug(whisk.DbgInfo, "unTarring unGzipped file\n")
            err = unpackTar("temp.tar")
            if err != nil {
                whisk.Debug(whisk.DbgError, "unpackTar(temp.tar) failure: %s\n", err)
                errStr := fmt.Sprintf("Error untaring file %s: %s", "temp.tar", err)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        // Future SDKs may require other unpacking procedures not yet covered here....
    }

    return nil
}

func init() {
    sdkCmd.AddCommand(sdkInstallCmd)

    sdkMap = make(map[string]*sdkInfo)
    sdkMap["docker"] = &sdkInfo{ UrlPath: "blackbox-0.1.0.tar.gz", FileName: "blackbox-0.1.0.tar.gz", isGzTar: true, Unpack: true, UnpackDir: "dockerSkeleton"}
    sdkMap["ios"] = &sdkInfo{ UrlPath: "OpenWhiskIOSStarterApp.zip", FileName: "OpenWhiskIOSStarterApp.zip", IsZip: true, Unpack: false}
}
