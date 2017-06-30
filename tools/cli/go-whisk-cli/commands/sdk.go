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
    "os/user"
    "io/ioutil"
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

var sdkInstallCmd = &cobra.Command{
    Use:   "install COMPONENT",
    Short: wski18n.T("install SDK artifacts"),
    Long: wski18n.T("install SDK artifacts, where valid COMPONENT values are docker, ios, and bashauto"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := wski18n.T("The SDK component argument is missing. One component (docker, ios, or bashauto) must be specified")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        component := strings.ToLower(args[0])
        switch component {
        case "docker":
            err = dockerInstall()
        case "ios":
            err = iOSInstall()
        case "bashauto":
            whisk.Debug(whisk.DbgInfo, "Running bashauto script\n")
            if err = WskCmd.GenBashCompletion(os.Stdout); err != nil {
                whisk.Debug(whisk.DbgError, "GenBashCompletion error: %s\n", err)
                errStr := wski18n.T("Unable to generate bashauto-completion {{.err}}",
                        map[string]interface{}{"err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            if flags.sdk.bashrc {
                if err := addToBash(); err != nil {
                    whisk.Debug(whisk.DbgError, "Flag --bashrc failed: %s\n", err)
                    errStr := wski18n.T("Unable to append .bashrc: {{.err}}",
                        map[string]interface{}{"err": err})
                    werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                    return werr
                }
            }
        default:
            whisk.Debug(whisk.DbgError, "Invalid component argument '%s'\n", component)
            errStr := wski18n.T("The SDK component argument '{{.component}}' is invalid. Valid components are docker, ios and bashauto",
                    map[string]interface{}{"component": component})
            err = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        }

        if err != nil {
            return err
        }
        return nil
    },
}

// addToBash() attempts to find user's .bashrc and append the line
// "eval \"`wsk sdk install bashauto`\"" to it unless the line already exists
func addToBash() error {
    var usr *user.User
    var bash *os.File
    var exists bool
    var err error

    bashCheck := "wsk sdk install bashauto"
    bashCmd := "eval \"`wsk sdk install bashauto`\""

    if usr, err = user.Current(); err != nil {
        whisk.Debug(whisk.DbgError, "Couldn't find User Directory: %s\n", err)
        return err
    }
    whisk.Debug(whisk.DbgInfo, "Found current user %s\n", usr.HomeDir)
    bashLoc := fmt.Sprintf("%s/%s", usr.HomeDir, ".bashrc")
    if bash, err = os.OpenFile(bashLoc, os.O_APPEND|os.O_WRONLY, 0600); err != nil {
        whisk.Debug(whisk.DbgError, "Could not find .bashrc: %s\n", err)
        return err
    }
    defer bash.Close()
    if exists, err = lineExists(bashLoc, bashCheck); err != nil {
        return err
    }
    if exists == false {
        whisk.Debug(whisk.DbgInfo, "Attempting to append .bashrc\n")
        if _, err :=  bash.WriteString(bashCmd); err != nil {
            whisk.Debug(whisk.DbgError, "Could not append .bashrc: %s\n", err)
            return err
        }
        whisk.Debug(whisk.DbgInfo, "Successfully appended .bashrc\n")
    }

    return nil
}

// lineExists(string, string) checks to see if a string is contained in a specified file
// Returns a boolean (true meaning the line exists) and any errors
func lineExists(fileLoc string , text string) (bool, error) {
	if read, err := ioutil.ReadFile(fileLoc); err != nil {
		whisk.Debug(whisk.DbgError, "Could not find %s due to: %s\n", fileLoc, err)
		return false, err
	} else if strings.Contains(string(read), text) {
		whisk.Debug(whisk.DbgInfo, "String '%s' was found in %s\n", text, fileLoc)
		return true, nil
	}
	whisk.Debug(whisk.DbgInfo, "Did not find string '%s' in %s\n", text, fileLoc)

	return false, nil
}

func dockerInstall() error {
    var err error

    targetFile := sdkMap[SDK_DOCKER_COMPONENT_NAME].FileName
    if _, err = os.Stat(targetFile); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", targetFile)
        errStr := wski18n.T("The file {{.name}} already exists.  Delete it and retry.",
            map[string]interface{}{"name": targetFile})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if err = sdkInstall(SDK_DOCKER_COMPONENT_NAME); err != nil {
        whisk.Debug(whisk.DbgError, "sdkInstall(%s) failed: %s\n", SDK_DOCKER_COMPONENT_NAME, err)
        errStr := wski18n.T("The {{.component}} SDK installation failed: {{.err}}",
                map[string]interface{}{"component": SDK_DOCKER_COMPONENT_NAME, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    fmt.Printf(
        wski18n.T("Downloaded OpenWhisk iOS starter app. Unzip {{.name}} and open the project in Xcode.\n",
            map[string]interface{}{"name": sdkMap[SDK_IOS_COMPONENT_NAME].FileName}))
    return nil
}

func sdkInstall(componentName string) error {
    targetFile := sdkMap[componentName].FileName
    if _, err := os.Stat(targetFile); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", targetFile)
        errStr := wski18n.T("The file {{.name}} already exists.  Delete it and retry.",
                map[string]interface{}{"name": targetFile})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    resp, err := client.Sdks.Install(sdkMap[componentName].UrlPath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "client.Sdks.Install(%s) failed: %s\n", sdkMap[componentName].UrlPath, err)
        errStr := wski18n.T("Unable to retrieve '{{.urlpath}}' SDK: {{.err}}",
                map[string]interface{}{"urlpath": sdkMap[componentName].UrlPath, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if resp.Body == nil {
        whisk.Debug(whisk.DbgError, "SDK Install HTTP response has no body\n")
        errStr := wski18n.T("Server failed to send the '{{.component}}' SDK: {{.err}}",
                map[string]interface{}{"name": componentName, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Create the SDK file
    sdkfile, err := os.Create(targetFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failure: %s\n", targetFile, err)
        errStr := wski18n.T("Error creating SDK file {{.name}}: {{.err}}",
                map[string]interface{}{"name": targetFile, "err": err})
        werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Read the HTTP response body and write it to the SDK file
    whisk.Debug(whisk.DbgInfo, "Reading SDK file from HTTP response body\n")
    _, err = io.Copy(sdkfile, resp.Body)
    if err != nil {
        whisk.Debug(whisk.DbgError, "io.Copy() of resp.Body into sdkfile failure: %s\n", err)
        errStr := wski18n.T("Error copying server response into file: {{.err}}",
                map[string]interface{}{"err": err})
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
            errStr := wski18n.T("The directory {{.name}} already exists.  Delete it and retry.",
                    map[string]interface{}{"name": targetdir})
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
                errStr := wski18n.T("Error unGzipping file {{.name}}: {{.err}}",
                    map[string]interface{}{"name": targetFile, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            defer os.Remove("temp.tar")

            whisk.Debug(whisk.DbgInfo, "unTarring unGzipped file\n")
            err = unpackTar("temp.tar")
            if err != nil {
                whisk.Debug(whisk.DbgError, "unpackTar(temp.tar) failure: %s\n", err)
                errStr := wski18n.T("Error untarring file {{.name}}: {{.err}}",
                        map[string]interface{}{"name": "temp.tar", "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        // Future SDKs may require other unpacking procedures not yet covered here....
    }

    return nil
}

func init() {
	sdkInstallCmd.Flags().BoolVarP(&flags.sdk.bashrc, "bashrc", "b", false, wski18n.T("adds bashauto-completion install command to .bashrc"))

    sdkCmd.AddCommand(sdkInstallCmd)

    sdkMap = make(map[string]*sdkInfo)
    sdkMap["docker"] = &sdkInfo{ UrlPath: "blackbox-0.1.0.tar.gz", FileName: "blackbox-0.1.0.tar.gz", isGzTar: true, Unpack: true, UnpackDir: "dockerSkeleton"}
    sdkMap["ios"] = &sdkInfo{ UrlPath: "OpenWhiskIOSStarterApp.zip", FileName: "OpenWhiskIOSStarterApp.zip", IsZip: true, Unpack: false}
}
