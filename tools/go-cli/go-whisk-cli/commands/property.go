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
    "bufio"
    "errors"
    "fmt"
    "os"
    "strings"
    "net/url"

    "github.com/mitchellh/go-homedir"
    "github.com/spf13/cobra"

    "../../go-whisk/whisk"
)

var Properties struct {
    Auth       string
    APIHost    string
    APIVersion string
    APIBuild   string
    APIBuildNo string
    CLIVersion string
    Namespace  string
    PropsFile  string
}

const DefaultAuth       string = ""
const DefaultAPIHost    string = "openwhisk.ng.bluemix.net"
const DefaultAPIVersion string = "v1"
const DefaultAPIBuild   string = ""
const DefaultAPIBuildNo string = ""
const DefaultNamespace  string = "_"
const DefaultPropsFile  string = "~/.wskprops"

var propertyCmd = &cobra.Command{
    Use:   "property",
    Short: "work with whisk properties",
}

var propertySetCmd = &cobra.Command{
    Use:            "set",
    Short:          "set property",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var okMsg string

        // get current props
        props, err := readProps(Properties.PropsFile)
        if err != nil {
            whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf("Unable to set the property value: %s", err)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // read in each flag, update if necessary

        if auth := flags.global.auth; len(auth) > 0 {
            props["AUTH"] = auth
            okMsg = fmt.Sprint("ok: whisk auth set")
        }

        if apiHost := flags.property.apihostSet; len(apiHost) > 0 {
            props["APIHOST"] = apiHost
            okMsg = fmt.Sprintf("ok: whisk API host set to '%s'", apiHost)
        }

        if apiVersion := flags.property.apiversionSet; len(apiVersion) > 0 {
            props["APIVERSION"] = apiVersion
            okMsg = fmt.Sprintf("ok: whisk API version set to '%s'", apiVersion)
        }

        if namespace := flags.property.namespaceSet; len(namespace) > 0 {
            namespaces, _, err := client.Namespaces.List()
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Namespaces.List() failed: %s\n", err)
                errStr := fmt.Sprintf("Authenticated user does not have namespace '%s'; set command failed: %s", namespace, err)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }

            whisk.Debug(whisk.DbgInfo, "Validating namespace '%s' is in user namespace list %#v\n", namespace, namespaces)
            var validNamespace bool
            for _, ns := range namespaces {
                if ns.Name == namespace {
                    whisk.Debug(whisk.DbgInfo, "Namespace '%s' is valid\n", namespace)
                    validNamespace = true
                }
            }

            if !validNamespace {
                whisk.Debug(whisk.DbgError, "Namespace '%s' is invalid\n", namespace)
                errStr := fmt.Sprintf("Invalid namespace %s", namespace)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }

            props["NAMESPACE"] = namespace
            okMsg = fmt.Sprintf("ok: whisk namespace set to '%s'", namespace)
        }

        err = writeProps(Properties.PropsFile, props)
        if err != nil {
            whisk.Debug(whisk.DbgError, "writeProps(%s, %#v) failed: %s\n", Properties.PropsFile, props, err)
            errStr := fmt.Sprintf("Unable to set the property value: %s", err)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Println(okMsg)
        return nil
    },
}

var propertyUnsetCmd = &cobra.Command{
    Use:            "unset",
    Short:          "unset property",
    SilenceUsage:   true,
    SilenceErrors:  true,
    RunE: func(cmd *cobra.Command, args []string) error {
        var okMsg string
        props, err := readProps(Properties.PropsFile)
        if err != nil {
            whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf("Unable to unset the property value: %s", err)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // read in each flag, update if necessary

        if flags.property.auth {
            delete(props, "AUTH")
            okMsg = fmt.Sprint("ok: whisk auth deleted")
            if len(DefaultAuth) > 0 {
                okMsg += fmt.Sprintf("; the default value of '%s' will be used.\n", DefaultAuth)
            } else {
                okMsg += fmt.Sprint("; no default value will be used.")
            }
        }

        if flags.property.namespace {
            delete(props, "NAMESPACE")
            okMsg = fmt.Sprint("ok: whisk namespace deleted")
            if len(DefaultNamespace) > 0 {
                okMsg += fmt.Sprintf("; the default value of '%s' will be used.\n", DefaultNamespace)
            } else {
                okMsg += fmt.Sprint("; there is no default value that can be used.")
            }
        }

        if flags.property.apihost {
            delete(props, "APIHOST")
            okMsg = fmt.Sprint("whisk API host deleted")
            if len(DefaultAPIHost) > 0 {
                okMsg += fmt.Sprintf("; the default value of '%s' will be used.\n", DefaultAPIHost)
            } else {
                okMsg += fmt.Sprint("; there is no default value that can be used.")
            }
        }

        if flags.property.apiversion {
            delete(props, "APIVERSION")
            okMsg = fmt.Sprint("ok: whisk API version deleted")
            if len(DefaultAPIVersion) > 0 {
                okMsg += fmt.Sprintf("; the default value of '%s' will be used.\n", DefaultAPIVersion)
            } else {
                okMsg += fmt.Sprint("; there is no default value that can be used.")
            }
        }

        err = writeProps(Properties.PropsFile, props)
        if err != nil {
            whisk.Debug(whisk.DbgError, "writeProps(%s, %#v) failed: %s\n", Properties.PropsFile, props, err)
            errStr := fmt.Sprintf("Unable to unset the property value: %s", err)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Println(okMsg)
        if err = loadProperties(); err != nil {
            whisk.Debug(whisk.DbgError, "loadProperties() failed: %s\n", err)
        }
        return nil
    },
}

var propertyGetCmd = &cobra.Command{
    Use:            "get",
    Short:          "get property",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        // If no property is explicitly specified, default to all properties
        if !(flags.property.all || flags.property.auth ||
             flags.property.apiversion || flags.property.cliversion ||
             flags.property.namespace || flags.property.apibuild ||
             flags.property.apibuildno) {
            flags.property.all = true
        }

        if flags.property.all || flags.property.auth {
            fmt.Println("whisk auth\t\t", Properties.Auth)
        }

        if flags.property.all || flags.property.apihost {
            fmt.Println("whisk API host\t\t", Properties.APIHost)
        }

        if flags.property.all || flags.property.apiversion {
            fmt.Println("whisk API version\t", Properties.APIVersion)
        }

        if flags.property.all || flags.property.namespace {
            fmt.Println("whisk namespace\t\t", Properties.Namespace)
        }

        if flags.property.all || flags.property.cliversion {
            fmt.Println("whisk CLI version\t", Properties.CLIVersion)
        }

        if flags.property.all || flags.property.apibuild || flags.property.apibuildno {
            info, _, err := client.Info.Get()
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Info.Get() failed: %s\n", err)
                info = &whisk.Info{}
                info.Build = "Unknown"
                info.BuildNo = "Unknown"
            }
            if flags.property.all || flags.property.apibuild {
                fmt.Println("whisk API build\t\t", info.Build)
            }
            if flags.property.all || flags.property.apibuildno {
                fmt.Println("whisk API build number\t", info.BuildNo)
            }
            if err != nil {
                errStr := fmt.Sprintf("Unable to obtain API build information: %s", err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        return nil
    },
}

func init() {
    propertyCmd.AddCommand(
        propertySetCmd,
        propertyUnsetCmd,
        propertyGetCmd,
    )

    // need to set property flags as booleans instead of strings... perhaps with boolApihost...
    propertyGetCmd.Flags().BoolVar(&flags.property.auth, "auth", false, "authorization key")
    propertyGetCmd.Flags().BoolVar(&flags.property.apihost, "apihost", false, "whisk API host")
    propertyGetCmd.Flags().BoolVar(&flags.property.apiversion, "apiversion", false, "whisk API version")
    propertyGetCmd.Flags().BoolVar(&flags.property.apibuild, "apibuild", false, "whisk API build version")
    propertyGetCmd.Flags().BoolVar(&flags.property.apibuildno, "apibuildno", false, "whisk API build number")
    propertyGetCmd.Flags().BoolVar(&flags.property.cliversion, "cliversion", false, "whisk CLI version")
    propertyGetCmd.Flags().BoolVar(&flags.property.namespace, "namespace", false, "authorization key")
    propertyGetCmd.Flags().BoolVar(&flags.property.all, "all", false, "all properties")

    propertySetCmd.Flags().StringVarP(&flags.global.auth, "auth", "u", "", "authorization key")
    propertySetCmd.Flags().StringVar(&flags.property.apihostSet, "apihost", "", "whisk API host")
    propertySetCmd.Flags().StringVar(&flags.property.apiversionSet, "apiversion", "", "whisk API version")
    propertySetCmd.Flags().StringVar(&flags.property.namespaceSet, "namespace", "", "whisk namespace")

    propertyUnsetCmd.Flags().BoolVar(&flags.property.auth, "auth", false, "authorization key")
    propertyUnsetCmd.Flags().BoolVar(&flags.property.apihost, "apihost", false, "whisk API host")
    propertyUnsetCmd.Flags().BoolVar(&flags.property.apiversion, "apiversion", false, "whisk API version")
    propertyUnsetCmd.Flags().BoolVar(&flags.property.namespace, "namespace", false, "whisk namespace")

}

func setDefaultProperties() {
    Properties.Auth = DefaultAuth
    Properties.Namespace = DefaultNamespace
    Properties.APIHost = DefaultAPIHost
    Properties.APIBuild = DefaultAPIBuild
    Properties.APIBuildNo = DefaultAPIBuildNo
    Properties.APIVersion = DefaultAPIVersion
    Properties.PropsFile = DefaultPropsFile
    // Properties.CLIVersion value is set from main's init()
}

func getPropertiesFilePath() (propsFilePath string, werr error) {
    // Environment variable overrides the default properties file path
    if propsFilePath = os.Getenv("WSK_CONFIG_FILE"); len(propsFilePath) > 0 {
        whisk.Debug(whisk.DbgInfo, "Using properties file '%s' from WSK_CONFIG_FILE environment variable\n", propsFilePath)
        return propsFilePath, nil
    } else {
        var err error

        propsFilePath, err = homedir.Expand(Properties.PropsFile)

        if err != nil {
            whisk.Debug(whisk.DbgError, "homedir.Expand(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf("Unable to locate properties file '%s'; error %s", Properties.PropsFile, err)
            werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return propsFilePath, werr
        }

        whisk.Debug(whisk.DbgInfo, "Using properties file home dir '%s'\n", propsFilePath)
    }

    return propsFilePath, nil
}

func loadProperties() error {
    var err error

    setDefaultProperties()

    Properties.PropsFile, err = getPropertiesFilePath()
    if err != nil {
        return nil
        //whisk.Debug(whisk.DbgError, "getPropertiesFilePath() failed: %s\n", err)
        //errStr := fmt.Sprintf("Unable to load the properties file: %s", err)
        //werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        //return werr
    }

    props, err := readProps(Properties.PropsFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
        errStr := fmt.Sprintf("Unable to read the properties file '%s': %s", Properties.PropsFile, err)
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if authToken, hasProp := props["AUTH"]; hasProp {
        Properties.Auth = authToken
    }

    if authToken := os.Getenv("WHISK_AUTH"); len(authToken) > 0 {
        Properties.Auth = authToken
    }

    if apiVersion, hasProp := props["APIVERSION"]; hasProp {
        Properties.APIVersion = apiVersion
    }

    if apiVersion := os.Getenv("WHISK_APIVERSION"); len(apiVersion) > 0 {
        Properties.APIVersion = apiVersion
    }

    if apiHost, hasProp := props["APIHOST"]; hasProp {
        Properties.APIHost = apiHost
    }

    if apiHost := os.Getenv("WHISK_APIHOST"); len(apiHost) > 0 {
        Properties.APIHost = apiHost
    }

    if namespace, hasProp := props["NAMESPACE"]; hasProp {
        Properties.Namespace = namespace
    }

    if namespace := os.Getenv("WHISK_NAMESPACE"); len(namespace) > 0 {
        Properties.Namespace = namespace
    }

    return nil
}

func parseConfigFlags(cmd *cobra.Command, args []string) error {

    if auth := flags.global.auth; len(auth) > 0 {
        Properties.Auth = auth
        if client != nil {
            client.Config.AuthToken = auth
        }
    }

    if namespace := flags.property.namespaceSet; len(namespace) > 0 {
        Properties.Namespace = namespace
        if client != nil {
            client.Config.Namespace = namespace
        }
    }

    if apiVersion := flags.global.apiversion; len(apiVersion) > 0 {
        Properties.APIVersion = apiVersion
        if client != nil {
            client.Config.Version = apiVersion
        }
    }

    if apiHost := flags.global.apihost; len(apiHost) > 0 {
        Properties.APIHost = apiHost

        if client != nil {
            client.Config.Host = apiHost

            // Place the host name (or ip addr) in whisk base URL string and parse
            // it to create a URL object.  Parsing will also validate the URL providing
            // a sanity check on the host/ip format
            var apiHostBaseUrl = fmt.Sprintf("https://%s/api/", Properties.APIHost)
            baseURL, err := url.Parse(apiHostBaseUrl)
            if err != nil {
                whisk.Debug(whisk.DbgError, "url.Parse(%s) failed: %s\n", apiHostBaseUrl, err)
                errStr := fmt.Sprintf("Invalid host address: %s", err)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            client.Config.BaseURL = baseURL
        }
    }

    if flags.global.debug {
        whisk.SetDebug(true)
    }
    if flags.global.verbose {
        whisk.SetVerbose(true)
    }

    return nil
}

func readProps(path string) (map[string]string, error) {

    props := map[string]string{}

    file, err := os.Open(path)
    if err != nil {
        // If file does not exist, just return props
        whisk.Debug(whisk.DbgWarn, "Unable to read whisk properties file '%s' (file open error: %s); falling back to default properties\n" ,path, err)
        return props, nil
    }
    defer file.Close()

    lines := []string{}
    scanner := bufio.NewScanner(file)
    for scanner.Scan() {
        lines = append(lines, scanner.Text())
    }

    props = map[string]string{}
    for _, line := range lines {
        kv := strings.Split(line, "=")
        if len(kv) != 2 {
            // Invalid format; skip
            continue
        }
        props[kv[0]] = kv[1]
    }

    return props, nil

}

func writeProps(path string, props map[string]string) error {

    file, err := os.Create(path)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", path, err)
        errStr := fmt.Sprintf("Whisk properties file write failue: %s", err)
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer file.Close()

    writer := bufio.NewWriter(file)
    defer writer.Flush()
    for key, value := range props {
        line := fmt.Sprintf("%s=%s", strings.ToUpper(key), value)
        _, err = fmt.Fprintln(writer, line)
        if err != nil {
            whisk.Debug(whisk.DbgError, "fmt.Fprintln() write to '%s' failed: %s\n", path, err)
            errStr := fmt.Sprintf("Whisk properties file write failue: %s", err)
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}
