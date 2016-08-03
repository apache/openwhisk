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
    "encoding/base64"
    "errors"
    "fmt"
    "io/ioutil"
    "os"
    "os/exec"
    "path/filepath"
    "strings"

    "../../go-whisk/whisk"

    "github.com/spf13/cobra"
    "encoding/json"
)

//////////////
// Commands //
//////////////

var actionCmd = &cobra.Command{
    Use:   "action",
    Short: "work with actions",
}

var actionCreateCmd = &cobra.Command{
    Use:   "create <name string> <artifact string>",
    Short: "create a new action",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        if len(args) < 2 {
            whisk.Debug(whisk.DbgError, "Action create command must have at least two arguments\n")
            errMsg := fmt.Sprintf("Invalid argument(s). An action name and artifact are required.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if len(args) > 2 {
            whisk.Debug(whisk.DbgError, "Action create command must not have more than two arguments\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[2:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        action, sharedSet, err := parseAction(cmd, args)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAction(%s, %s) error: %s\n", cmd, args, err)
            errMsg := fmt.Sprintf("Unable to parse action command arguments: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        _, _, err = client.Actions.Insert(action, sharedSet, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Insert(%#v, %t, false) error: %s\n", action, sharedSet, err)
            errMsg := fmt.Sprintf("Unable to create action: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_NETWORK,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        fmt.Printf("ok: created action %s\n", action.Name)
        return nil
    },
}

var actionUpdateCmd = &cobra.Command{
    Use:   "update <name string> <artifact string>",
    Short: "update an existing action",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        if len(args) < 1 {
            whisk.Debug(whisk.DbgError, "Action update command must have at least one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s). An action name is required.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if len(args) > 2 {
            whisk.Debug(whisk.DbgError, "Action update command must not have more than two arguments\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[2:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        action, sharedSet, err := parseAction(cmd, args)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAction(%s, %s) error: %s\n", cmd, args, err)
            errMsg := fmt.Sprintf("Unable to parse action command arguments: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        _, _, err = client.Actions.Insert(action, sharedSet, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Insert(%#v, %t, false) error: %s\n", action, sharedSet, err)
            errMsg := fmt.Sprintf("Unable to update action: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_NETWORK,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        fmt.Printf("ok: updated action %s\n", action.Name)
        return nil
    },
}

var actionInvokeCmd = &cobra.Command{
    Use:   "invoke <name string>",
    Short: "invoke action",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if len(args) < 1 {
            whisk.Debug(whisk.DbgError, "Action invoke command must have at least one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s). An action name is required.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if len(args) > 1 {
            whisk.Debug(whisk.DbgError, "Action invoke command must not have more than one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }
        client.Namespace = qName.namespace

        var payload *json.RawMessage

        if len(flags.common.param) > 0 {
            whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)

            parameters, err := getJSONFromArguments(flags.common.param, false)
            if err != nil {
                whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, false) failed: %s\n", flags.common.param, err)
                errMsg := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }

            payload = parameters
        }

        if payload == nil {
            data := []byte("{}")
            payload = (*json.RawMessage)(&data)
        }

        outputStream := os.Stdout

        activation, _, err := client.Actions.Invoke(qName.entityName, payload, flags.common.blocking)
        if err != nil {
            whiskErr, isWhiskErr := err.(*whisk.WskError)

            if (isWhiskErr && whiskErr.ApplicationError != true) || !isWhiskErr {
                whisk.Debug(whisk.DbgError, "client.Actions.Invoke(%s, %s, %t) error: %s\n", qName.entityName, payload,
                    flags.common.blocking, err)
                errMsg := fmt.Sprintf("Unable to invoke action '%s': %s", qName.entityName, err)
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            } else {
                outputStream = os.Stderr
            }
        }

        if flags.common.blocking && flags.action.result {
            printJsonNoColor(activation.Response.Result, outputStream)
        } else if flags.common.blocking {
            fmt.Printf("ok: invoked /%s/%s with id %s\n", qName.namespace, qName.entityName, activation.ActivationID)
            printJsonNoColor(activation, outputStream)
        } else {
            fmt.Printf("ok: invoked /%s/%s with id %s\n", qName.namespace, qName.entityName, activation.ActivationID)
        }

        return err
    },
}

var actionGetCmd = &cobra.Command{
    Use:   "get <name string>",
    Short: "get action",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if len(args) < 1 {
            whisk.Debug(whisk.DbgError, "Action get command must have at least one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s). An action name is required.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if len(args) > 1 {
            whisk.Debug(whisk.DbgError, "Action get command must not have more than one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }
        client.Namespace = qName.namespace

        action, _, err := client.Actions.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Get(%s) error: %s\n", qName.entityName, err)
            errMsg := fmt.Sprintf("Unable to get action: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        // print out response
        if flags.common.summary {
            fmt.Printf("action /%s/%s\n", action.Namespace, action.Name)
        } else {
            fmt.Printf("ok: got action %s\n", qName.entityName)
            printJsonNoColor(action)
        }

        return nil
    },
}

var actionDeleteCmd = &cobra.Command{
    Use:   "delete <name string>",
    Short: "delete action",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        if len(args) < 1 {
            whisk.Debug(whisk.DbgError, "Action delete command must have at least one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s). An action name is required.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if len(args) > 1 {
            whisk.Debug(whisk.DbgError, "Action delete command must not have more than one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }
        client.Namespace = qName.namespace

        _, err = client.Actions.Delete(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Delete(%s) error: %s\n", qName.entityName, err)
            errMsg := fmt.Sprintf("Unable to delete action: %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        fmt.Printf("ok: deleted action %s\n", qName.entityName)

        return nil
    },
}

var actionListCmd = &cobra.Command{
    Use:   "list [namespace string]",
    Short: "list all actions",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var qName qualifiedName
        var err error

        if len(args) == 1 {
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }

            if len(qName.namespace) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
                errMsg := fmt.Sprintf("No valid namespace detected. Make sure that namespace argument is preceded by a \"/\"")
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
            client.Namespace = qName.namespace
        } else if len(args) > 1 {
            whisk.Debug(whisk.DbgError, "Action list command must not have more than one argument\n")
            errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        }

        options := &whisk.ActionListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }

        actions, _, err := client.Actions.List(qName.entityName, options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.List(%s, %#v) error: %s\n", qName.entityName, options, err)
            errMsg := fmt.Sprintf("Unable to list action(s): %s", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_NETWORK,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        printList(actions)
        return nil
    },
}

func getJavaClasses(classes []string) ([]string){
    var res []string

    for i := 0; i < len(classes); i++ {
        if strings.HasSuffix(classes[i], ".class") {
            classes[i] = classes[i][0: len(classes[i]) - 6]
            classes[i] = strings.Replace(classes[i], "/", ".", -1)
            res = append(res, classes[i])
        }
    }

    return res
}

func findMainJarClass(jarFile string) (string, error) {
    signature := "public static com.google.gson.JsonObject main(com.google.gson.JsonObject);"

    whisk.Debug(whisk.DbgInfo, "unjaring '%s'\n", jarFile)
    stdOut, err := exec.Command("jar", "-tf", jarFile).Output()
    if err != nil {
        whisk.Debug(whisk.DbgError, "unjar of '%s' failed: %s\n", jarFile, err)
        return "", err
    }

    whisk.Debug(whisk.DbgInfo, "jar stdout:\n%s\n", stdOut)
    output := string(stdOut[:])
    output = strings.Replace(output, "\r", "", -1)  // Windows jar adds \r chars that needs removing
    outputArr := strings.Split(output, "\n")
    classes := getJavaClasses(outputArr)

    whisk.Debug(whisk.DbgInfo, "jar '%s' has %d classes\n", jarFile, len(classes))
    for i := 0; i < len(classes); i++ {
        whisk.Debug(whisk.DbgInfo, "javap -public -cp '%s'\n", classes[i])
        stdOut, err = exec.Command("javap", "-public", "-cp", jarFile, classes[i]).Output()
        if err != nil {
            whisk.Debug(whisk.DbgError, "javap of class '%s' in jar '%s' failed: %s\n", classes[i], jarFile, err)
            return "", err
        }

        output := string(stdOut[:])
        whisk.Debug(whisk.DbgInfo, "javap '%s' output:\n%s\n", classes[i], output)

        if strings.Contains(output, signature) {
            return classes[i], nil
        }
    }

    errMsg := fmt.Sprintf("Could not find 'main' method in %s", jarFile)
    whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

    return "", whiskErr
}

func parseAction(cmd *cobra.Command, args []string) (*whisk.Action, bool, error) {
    var err error
    var shared, sharedSet bool
    var artifact string
    var limits *whisk.Limits

    qName := qualifiedName{}
    qName, err = parseQualifiedName(args[0])
    if err != nil {
        whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
        errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, sharedSet, whiskErr
    }
    client.Namespace = qName.namespace

    if len(args) == 2 {
        artifact = args[1]
    }

    if flags.action.shared == "yes" {
        shared = true
        sharedSet = true
    } else if flags.action.shared == "no" {
        shared = false
        sharedSet = true
    } else {
        sharedSet = false
    }

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromArguments(flags.common.param, true)
    if err != nil {
        whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
        errMsg := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, sharedSet, whiskErr
    }

    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromArguments(flags.common.annotation, true)
    if err != nil {
        whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
        errMsg := fmt.Sprintf("Invalid annotation argument '%#v': %s", flags.common.annotation, err)
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, sharedSet, whiskErr
    }

    // Only include the memory and timeout limit if set
    if flags.action.memory > -1 || flags.action.timeout > -1 || flags.action.logsize > -1 {
        limits = new(whisk.Limits)
        if flags.action.memory > -1 {
            limits.Memory = &flags.action.memory
        }
        if flags.action.timeout > -1 {
            limits.Timeout = &flags.action.timeout
        }
        if flags.action.logsize > -1 {
            limits.Logsize = &flags.action.logsize
        }
        whisk.Debug(whisk.DbgInfo, "Action limits: %+v\n", limits)
    }

    action := new(whisk.Action)

    if flags.action.docker {
        action.Exec = new(whisk.Exec)
        action.Exec.Image = artifact
        action.Exec.Kind = "blackbox"
    } else if flags.action.copy {
        qNameCopy := qualifiedName{}
        qNameCopy, err = parseQualifiedName(args[1])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[1], err)
            errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[1], err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }
        client.Namespace = qNameCopy.namespace

        existingAction, _, err := client.Actions.Get(qNameCopy.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Get(%s) error: %s\n", qName.entityName, err)
            errMsg := fmt.Sprintf("Unable to obtain action '%s' to copy: %s", qName.entityName, err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }

        client.Namespace = qName.namespace
        action.Exec = existingAction.Exec
    } else if flags.action.sequence {
        currentNamespace := client.Config.Namespace
        client.Config.Namespace = "whisk.system"

        pipeAction, _, err := client.Actions.Get("system/pipe")
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Actions.Get(%s) error: %s\n", "system/pipe", err)
            errMsg := fmt.Sprintf("Unable to obtain the '%s' action needed for action sequencing: %s", "system/pipe", err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }

        if len(artifact) > 0 {
            actionList := "[{\"key\": \"_actions\", \"value\": ["
            actions := strings.Split(artifact, ",")

            for i := 0; i < len(actions); i++ {
                actionQName := qualifiedName{}
                actionQName, err = parseQualifiedName(actions[i])
                if err != nil {
                    whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", actions[i], err)
                    errMsg := fmt.Sprintf("'%s' is not a valid qualified name: %s", actions[i], err)
                    whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                    return nil, sharedSet, whiskErr
                }

                actionList = actionList + "\"/" + actionQName.namespace + "/" + actionQName.entityName + "\""
                if i < len(actions) -1 {
                    actionList = actionList + ", "
                }
            }

            actionList = actionList + "]}]"
            data := []byte(actionList)
            action.Parameters = (*json.RawMessage)(&data)
        } else {
            whisk.Debug(whisk.DbgError, "--sequence specified, but no sequence of actions was provided\n")
            errMsg := fmt.Sprintf("Comma separated action sequence is missing")
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }

        action.Exec = pipeAction.Exec
        client.Config.Namespace = currentNamespace

    } else if artifact != "" {
        ext := filepath.Ext(artifact)

        _, err := os.Stat(artifact)
        if err != nil {
            whisk.Debug(whisk.DbgError, "os.Stat(%s) error: %s\n", artifact, err)
            errMsg := fmt.Sprintf("File '%s' is not a valid file or it does not exist: %s", artifact, err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_USAGE,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

            return nil, sharedSet, whiskErr
        }

        file, err := ioutil.ReadFile(artifact)
        if err != nil {
            whisk.Debug(whisk.DbgError, "os.ioutil.ReadFile(%s) error: %s\n", artifact, err)
            errMsg := fmt.Sprintf("Unable to read '%s': %s", artifact, err)
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }

        if action.Exec == nil {
            action.Exec = new(whisk.Exec)
        }

        action.Exec.Code = string(file)

        if flags.action.kind == "swift:3" || flags.action.kind == "swift:3.0" || flags.action.kind == "swift:3.0.0" {
            action.Exec.Kind = "swift:3"
        } else if flags.action.kind == "nodejs:6" || flags.action.kind == "nodejs:6.0" || flags.action.kind == "nodejs:6.0.0" {
            action.Exec.Kind = "nodejs:6"
        } else if flags.action.kind == "nodejs:default" {
            action.Exec.Kind = "nodejs:default"
        } else if flags.action.kind == "nodejs" {
            action.Exec.Kind = "nodejs"
        } else if flags.action.kind == "swift" {
            action.Exec.Kind = "swift"
        } else if len(flags.action.kind) > 0 {
            whisk.Debug(whisk.DbgError, "--kind argument '%s' is not supported\n", flags.action.kind)
            errMsg := fmt.Sprintf("'%s' is not a supported action runtime", flags.action.kind)
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        } else if ext == ".swift" {
            action.Exec.Kind = "swift"
        } else if ext == ".js" {
            action.Exec.Kind = "nodejs:6"
        } else if ext == ".py" {
            action.Exec.Kind = "python"
        } else if ext == ".jar" {
            action.Exec.Code = ""
            action.Exec.Kind = "java"
            action.Exec.Jar = base64.StdEncoding.EncodeToString([]byte(string(file)))
            action.Exec.Main, err = findMainJarClass(artifact)

            if err != nil {
                return nil, sharedSet, err
            }
        } else {
            whisk.Debug(whisk.DbgError, "Action runtime exention '%s' is not supported\n", ext)
            errMsg := fmt.Sprintf("'%s' is not a supported action runtime", ext)
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
                whisk.DISPLAY_USAGE)
            return nil, sharedSet, whiskErr
        }
    }

    action.Name = qName.entityName
    action.Namespace = qName.namespace
    action.Publish = shared
    action.Annotations = annotations
    action.Limits = limits

    // If the action sequence is not already the Parameters value, set it to the --param parameter values
    if action.Parameters == nil && parameters != nil {
        action.Parameters = parameters
    }

    whisk.Debug(whisk.DbgInfo, "Parsed action struct: %#v\n", action)
    return action, sharedSet, nil
}



///////////
// Flags //
///////////

func init() {
    actionCreateCmd.Flags().BoolVar(&flags.action.docker, "docker", false, "treat artifact as docker image path on dockerhub")
    actionCreateCmd.Flags().BoolVar(&flags.action.copy, "copy", false, "treat artifact as the name of an existing action")
    actionCreateCmd.Flags().BoolVar(&flags.action.sequence, "sequence", false, "treat artifact as comma separated sequence of actions to invoke")
    actionCreateCmd.Flags().StringVar(&flags.action.kind, "kind", "", "the kind of the action runtime (example: swift:3, nodejs:6)")
    actionCreateCmd.Flags().StringVar(&flags.action.shared, "shared", "", "shared action (default: private)")
    actionCreateCmd.Flags().StringVar(&flags.action.xPackage, "package", "", "package")
    actionCreateCmd.Flags().IntVarP(&flags.action.timeout, "timeout", "t", -1, "the timeout limit in milliseconds when the action will be terminated")
    actionCreateCmd.Flags().IntVarP(&flags.action.memory, "memory", "m", -1, "the memory limit in MB of the container that runs the action")
    actionCreateCmd.Flags().IntVarP(&flags.action.logsize, "logsize", "l", -1, "the logsize limit in MB of the container that runs the action")
    actionCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    actionCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")

    actionUpdateCmd.Flags().BoolVar(&flags.action.docker, "docker", false, "treat artifact as docker image path on dockerhub")
    actionUpdateCmd.Flags().BoolVar(&flags.action.copy, "copy", false, "treat artifact as the name of an existing action")
    actionUpdateCmd.Flags().BoolVar(&flags.action.sequence, "sequence", false, "treat artifact as comma separated sequence of actions to invoke")
    actionUpdateCmd.Flags().StringVar(&flags.action.kind, "kind", "", "the kind of the action runtime (example: swift:3, nodejs:6)")
    actionUpdateCmd.Flags().StringVar(&flags.action.shared, "shared", "", "shared action (default: private)")
    actionUpdateCmd.Flags().StringVar(&flags.action.xPackage, "package", "", "package")
    actionUpdateCmd.Flags().IntVarP(&flags.action.timeout, "timeout", "t", -1, "the timeout limit in milliseconds when the action will be terminated")
    actionUpdateCmd.Flags().IntVarP(&flags.action.memory, "memory", "m", -1, "the memory limit in MB of the container that runs the action")
    actionUpdateCmd.Flags().IntVarP(&flags.action.logsize, "logsize", "l", -1, "the logsize limit in MB of the container that runs the action")
    actionUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    actionUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")

    actionInvokeCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "parameters")
    actionInvokeCmd.Flags().BoolVarP(&flags.common.blocking, "blocking", "b", false, "blocking invoke")
    actionInvokeCmd.Flags().BoolVarP(&flags.action.result, "result", "r", false, "show only activation result if a blocking activation (unless there is a failure)")

    actionGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, "summarize entity details")

    actionListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "skip this many entitites from the head of the collection")
    actionListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return this many entities from the collection")
    actionListCmd.Flags().BoolVar(&flags.common.full, "full", false, "include full entity description")

    actionCmd.AddCommand(
        actionCreateCmd,
        actionUpdateCmd,
        actionInvokeCmd,
        actionGetCmd,
        actionDeleteCmd,
        actionListCmd,
    )
}
