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
    "path/filepath"
    "io"
    "strings"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/fatih/color"
    "github.com/spf13/cobra"
    "github.com/mattn/go-colorable"
)

const MEMORY_LIMIT = 256
const TIMEOUT_LIMIT = 60000
const LOGSIZE_LIMIT = 10
const ACTIVATION_ID = "activationId"
const WEB_EXPORT_ANNOT = "web-export"
const RAW_HTTP_ANNOT = "raw-http"
const FINAL_ANNOT = "final"

var actionCmd = &cobra.Command{
    Use:   "action",
    Short: wski18n.T("work with actions"),
}

var actionCreateCmd = &cobra.Command{
    Use:           "create ACTION_NAME ACTION",
    Short:         wski18n.T("create a new action"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var action *whisk.Action
        var err error

        if whiskErr := checkArgs(
            args,
            1,
            2,
            "Action create",
            wski18n.T("An action name and code artifact are required.")); whiskErr != nil {
                return whiskErr
        }

        if action, err = parseAction(cmd, args, false); err != nil {
            return actionParseError(cmd, args, err)
        }

        if _, _, err = client.Actions.Insert(action, false); err != nil {
            return actionInsertError(action, err)
        }

        printActionCreated(action.Name)

        return nil
    },
}

var actionUpdateCmd = &cobra.Command{
    Use:           "update ACTION_NAME [ACTION]",
    Short:         wski18n.T("update an existing action, or create an action if it does not exist"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var action *whisk.Action
        var err error

        if whiskErr := checkArgs(
            args,
            1,
            2,
            "Action update",
            wski18n.T("An action name is required. A code artifact is optional.")); whiskErr != nil {
                return whiskErr
        }

        if action, err = parseAction(cmd, args, true); err != nil {
            return actionParseError(cmd, args, err)
        }

        if _, _, err = client.Actions.Insert(action, true); err != nil {
            return actionInsertError(action, err)
        }

        printActionUpdated(action.Name)

        return nil
    },
}

var actionInvokeCmd = &cobra.Command{
    Use:           "invoke ACTION_NAME",
    Short:         wski18n.T("invoke action"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var parameters interface{}
        var qualifiedName QualifiedName
        var paramArgs []string

        if whiskErr := checkArgs(
            args,
            1,
            1,
            "Action invoke",
            wski18n.T("An action name is required.")); whiskErr != nil {
                return whiskErr
        }

        if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
            return parseQualifiedNameError(args[0], err)
        }

        client.Namespace = qualifiedName.namespace
        paramArgs = flags.common.param

        if len(paramArgs) > 0 {
            if parameters, err = getJSONFromStrings(paramArgs, false); err != nil {
                return getJSONFromStringsParamError(paramArgs, false, err)
            }
        }
        if flags.action.result {flags.common.blocking = true}

        res, _, err := client.Actions.Invoke(
            qualifiedName.entityName,
            parameters,
            flags.common.blocking,
            flags.action.result)

        return handleInvocationResponse(qualifiedName, parameters, res, err)
    },
}

func handleInvocationResponse(
    qualifiedName QualifiedName,
    parameters interface{},
    result map[string]interface{},
    err error) (error) {
        if err == nil {
            printInvocationMsg(
                qualifiedName.namespace,
                qualifiedName.entityName,
                getValueFromJSONResponse(ACTIVATION_ID, result),
                result,
                color.Output)
        } else {
            if !flags.common.blocking {
                return handleInvocationError(err, qualifiedName.entityName, parameters)
            } else {
                if isBlockingTimeout(err) {
                    printBlockingTimeoutMsg(
                        qualifiedName.namespace,
                        qualifiedName.entityName,
                        getValueFromJSONResponse(ACTIVATION_ID, result))
                } else if isApplicationError(err) {
                    printInvocationMsg(
                        qualifiedName.namespace,
                        qualifiedName.entityName,
                        getValueFromJSONResponse(ACTIVATION_ID, result),
                        result,
                        colorable.NewColorableStderr())
                } else {
                    return handleInvocationError(err, qualifiedName.entityName, parameters)
                }
            }
        }

        return err
}

var actionGetCmd = &cobra.Command{
    Use:           "get ACTION_NAME [FIELD_FILTER]",
    Short:         wski18n.T("get action"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var field string
        var action *whisk.Action
        var qualifiedName QualifiedName

        if whiskErr := checkArgs(args, 1, 2, "Action get", wski18n.T("An action name is required.")); whiskErr != nil {
            return whiskErr
        }

        if len(args) > 1 {
            field = args[1]

            if !fieldExists(&whisk.Action{}, field) {
                return invalidFieldFilterError(field)
            }
        }

        if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
            return parseQualifiedNameError(args[0], err)
        }

        client.Namespace = qualifiedName.namespace

        if action, _, err = client.Actions.Get(qualifiedName.entityName); err != nil {
            return actionGetError(qualifiedName.entityName, err)
        }

        if flags.common.summary {
            printSummary(action)
        } else {
            if len(field) > 0 {
                printActionGetWithField(qualifiedName.entityName, field, action)
            } else {
                printActionGet(qualifiedName.entityName, action)
            }
        }

        return nil
    },
}

var actionDeleteCmd = &cobra.Command{
    Use:           "delete ACTION_NAME",
    Short:         wski18n.T("delete action"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var qualifiedName QualifiedName
        var err error

        if whiskErr := checkArgs(
            args,
            1,
            1,
            "Action delete",
            wski18n.T("An action name is required.")); whiskErr != nil {
                return whiskErr
        }

        if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
            return parseQualifiedNameError(args[0], err)
        }

        client.Namespace = qualifiedName.namespace

        if _, err = client.Actions.Delete(qualifiedName.entityName); err != nil {
            return actionDeleteError(qualifiedName.entityName, err)
        }

        printActionDeleted(qualifiedName.entityName)

        return nil
    },
}

var actionListCmd = &cobra.Command{
    Use:           "list [ NAMESPACE | PACKAGE_NAME ]",
    Short:         wski18n.T("list all actions in a namespace or actions contained in a package"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var qualifiedName QualifiedName
        var actions []whisk.Action
        var err error

        if whiskErr := checkArgs(
            args,
            0,
            1,
            "Action list",
            wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        if len(args) == 1 {
            if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
                return parseQualifiedNameError(args[0], err)
            }

            client.Namespace = qualifiedName.namespace
        }

        options := &whisk.ActionListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }

        if actions, _, err = client.Actions.List(qualifiedName.entityName, options); err != nil {
            return actionListError(qualifiedName.entityName, options, err)
        }

        printList(actions)

        return nil
    },
}

func parseAction(cmd *cobra.Command, args []string, update bool) (*whisk.Action, error) {
    var err error
    var existingAction *whisk.Action
    var paramArgs []string
    var annotArgs []string
    var parameters interface{}
    var annotations interface{}

    qualifiedName := QualifiedName{}

    if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
        return nil, parseQualifiedNameError(args[0], err)
    }

    client.Namespace = qualifiedName.namespace
    action := new(whisk.Action)
    action.Name = qualifiedName.entityName
    action.Namespace = qualifiedName.namespace
    action.Limits = getLimits(
        cmd.LocalFlags().Changed(MEMORY_FLAG),
        cmd.LocalFlags().Changed(LOG_SIZE_FLAG),
        cmd.LocalFlags().Changed(TIMEOUT_FLAG),
        flags.action.memory,
        flags.action.logsize,
        flags.action.timeout)

    paramArgs = flags.common.param
    annotArgs = flags.common.annotation

    if len(paramArgs) > 0 {
        if parameters, err = getJSONFromStrings(paramArgs, true); err != nil {
            return nil, getJSONFromStringsParamError(paramArgs, true, err)
        }

        action.Parameters = parameters.(whisk.KeyValueArr)
    }

    if len(annotArgs) > 0 {
        if annotations, err = getJSONFromStrings(annotArgs, true); err != nil {
            return nil, getJSONFromStringsAnnotError(annotArgs, true, err)
        }

        action.Annotations = annotations.(whisk.KeyValueArr)
    }

    if flags.action.copy {
        copiedQualifiedName := QualifiedName{}

        if copiedQualifiedName, err = parseQualifiedName(args[1]); err != nil {
            return nil, parseQualifiedNameError(args[1], err)
        }

        client.Namespace = copiedQualifiedName.namespace

        if existingAction, _, err = client.Actions.Get(copiedQualifiedName.entityName); err != nil {
            return nil, actionGetError(copiedQualifiedName.entityName, err)
        }

        client.Namespace = qualifiedName.namespace
        action.Exec = existingAction.Exec
        action.Parameters = append(action.Parameters, existingAction.Parameters...)
        action.Annotations = append(action.Annotations, existingAction.Annotations...)
    } else if flags.action.sequence {
        if len(args) == 2 {
            action.Exec = new(whisk.Exec)
            action.Exec.Kind = "sequence"
            action.Exec.Components = csvToQualifiedActions(args[1])
        } else {
            return nil, noArtifactError()
        }
    } else if len(args) > 1 || len(flags.action.docker) > 0 {
        action.Exec, err = getExec(args, flags.action)
        if err != nil {
            return nil, err
        }
    } else if !update {
        return nil, noArtifactError()
    }

    if cmd.LocalFlags().Changed(WEB_FLAG) {
        action.Annotations, err = webAction(flags.action.web, action.Annotations, qualifiedName.entityName, update)
    }

    whisk.Debug(whisk.DbgInfo, "Parsed action struct: %#v\n", action)

    return action, err
}

func getExec(args []string, params ActionFlags) (*whisk.Exec, error) {
    var err error
    var code string
    var exec *whisk.Exec

    exec = new(whisk.Exec)
    kind := params.kind
    isNative := params.native
    docker := params.docker
    mainEntry := params.main
    ext := ""

    if len(args) == 2 {
        artifact := args[1]
        ext = filepath.Ext(artifact)
        code, err = readFile(artifact)

        if err != nil {
            whisk.Debug(whisk.DbgError, "readFile(%s) error: %s\n", artifact, err)
            return nil, err
        }

        if ext == ".zip" || ext == ".jar" {
            // Base64 encode the file
            code = base64.StdEncoding.EncodeToString([]byte(code))
        }

        exec.Code = &code
    } else if len(args) == 1 && len(docker) == 0 {
        return nil, noArtifactError()
    } else if len(args) > 1 {
        return nil, noArtifactError()
    }

    if len(kind) > 0 {
        exec.Kind = kind
    } else if len(docker) > 0 || isNative {
        exec.Kind = "blackbox"
        if isNative {
            exec.Image = "openwhisk/dockerskeleton"
        } else {
            exec.Image = docker
        }
    } else if ext == ".swift" {
        exec.Kind = "swift:default"
    } else if ext == ".js" {
        exec.Kind = "nodejs:default"
    } else if ext == ".py" {
        exec.Kind = "python:default"
    } else if ext == ".jar" {
        exec.Kind = "java:default"
    } else {
        if ext == ".zip" {
            return nil, zipKindError()
        } else {
            return nil, extensionError(ext)
        }
    }

    // Error if entry point is not specified for Java
    if len(mainEntry) != 0 {
        exec.Main = mainEntry
    } else {
        if exec.Kind == "java" {
            return nil, javaEntryError()
        }
    }

    return exec, nil
}

func webAction(webMode string, annotations whisk.KeyValueArr, entityName string, fetch bool) (whisk.KeyValueArr, error){
    switch strings.ToLower(webMode) {
    case "yes":
        fallthrough
    case "true":
        return webActionAnnotations(fetch, annotations, entityName, addWebAnnotations)
    case "no":
        fallthrough
    case "false":
        return webActionAnnotations(fetch, annotations, entityName, deleteWebAnnotations)
    case "raw":
        return webActionAnnotations(fetch, annotations, entityName, addRawAnnotations)
    default:
        return nil, webInputError(webMode)
    }
}

type WebActionAnnotationMethod func(annotations whisk.KeyValueArr) (whisk.KeyValueArr)

func webActionAnnotations(
    fetchAnnotations bool,
    annotations whisk.KeyValueArr,
    entityName string,
    webActionAnnotationMethod WebActionAnnotationMethod) (whisk.KeyValueArr, error) {
        var action *whisk.Action
        var err error

        if annotations != nil || !fetchAnnotations {
            annotations = webActionAnnotationMethod(annotations)
        } else {
            if action, _, err = client.Actions.Get(entityName); err != nil {
                return nil, actionGetError(entityName, err)
            } else {
                annotations = webActionAnnotationMethod(action.Annotations)
            }
        }

        return annotations, nil
}

func addWebAnnotations(annotations whisk.KeyValueArr) (whisk.KeyValueArr) {
    annotations = deleteWebAnnotationKeys(annotations)
    annotations = addKeyValue(WEB_EXPORT_ANNOT, true, annotations)
    annotations = addKeyValue(RAW_HTTP_ANNOT, false, annotations)
    annotations = addKeyValue(FINAL_ANNOT, true, annotations)

    return annotations
}

func deleteWebAnnotations(annotations whisk.KeyValueArr) (whisk.KeyValueArr) {
    annotations = deleteWebAnnotationKeys(annotations)
    annotations = addKeyValue(WEB_EXPORT_ANNOT, false, annotations)
    annotations = addKeyValue(RAW_HTTP_ANNOT, false, annotations)
    annotations = addKeyValue(FINAL_ANNOT, false, annotations)

    return annotations
}

func addRawAnnotations(annotations whisk.KeyValueArr) (whisk.KeyValueArr) {
    annotations = deleteWebAnnotationKeys(annotations)
    annotations = addKeyValue(WEB_EXPORT_ANNOT, true, annotations)
    annotations = addKeyValue(RAW_HTTP_ANNOT, true, annotations)
    annotations = addKeyValue(FINAL_ANNOT, true, annotations)

    return annotations
}

func deleteWebAnnotationKeys(annotations whisk.KeyValueArr) (whisk.KeyValueArr) {
    annotations = deleteKey(WEB_EXPORT_ANNOT, annotations)
    annotations = deleteKey(RAW_HTTP_ANNOT, annotations)
    annotations = deleteKey(FINAL_ANNOT, annotations)

    return annotations
}

func getLimits(memorySet bool, logSizeSet bool, timeoutSet bool, memory int, logSize int, timeout int) (*whisk.Limits) {
    var limits *whisk.Limits

    if memorySet || logSizeSet || timeoutSet {
        limits = new(whisk.Limits)

        if memorySet {
            limits.Memory = &memory
        }

        if logSizeSet {
            limits.Logsize = &logSize
        }

        if timeoutSet {
            limits.Timeout = &timeout
        }
    }

    return limits
}

func nestedError(errorMessage string, err error) (error) {
    return whisk.MakeWskErrorFromWskError(
        errors.New(errorMessage),
        err,
        whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG,
        whisk.DISPLAY_USAGE)
}

func nonNestedError(errorMessage string) (error) {
    return whisk.MakeWskError(
        errors.New(errorMessage),
        whisk.EXITCODE_ERR_USAGE,
        whisk.DISPLAY_MSG,
        whisk.DISPLAY_USAGE)
}

func actionParseError(cmd *cobra.Command, args []string, err error) (error) {
    whisk.Debug(whisk.DbgError, "parseAction(%s, %s) error: %s\n", cmd, args, err)

    errMsg := wski18n.T(
        "Invalid argument(s). {{.required}}",
        map[string]interface{}{
            "required": err,
        })

    return nestedError(errMsg, err)
}

func actionInsertError(action *whisk.Action, err error) (error) {
    whisk.Debug(whisk.DbgError, "client.Actions.Insert(%#v, false) error: %s\n", action, err)

    errMsg := wski18n.T(
        "Unable to create action '{{.name}}': {{.err}}",
        map[string]interface{}{
            "name": action.Name,
            "err": err,
        })

    return nestedError(errMsg, err)
}

func getJSONFromStringsParamError(params []string, keyValueFormat bool, err error) (error) {
    whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, %t) failed: %s\n", params, keyValueFormat, err)

    errMsg := wski18n.T(
        "Invalid parameter argument '{{.param}}': {{.err}}",
        map[string]interface{}{
            "param": fmt.Sprintf("%#v", params),
            "err": err,
        })

    return nestedError(errMsg, err)
}

func getJSONFromStringsAnnotError(annots []string, keyValueFormat bool, err error) (error) {
    whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, %t) failed: %s\n", annots, keyValueFormat, err)

    errMsg := wski18n.T(
        "Invalid annotation argument '{{.annotation}}': {{.err}}",
        map[string]interface{}{
            "annotation": fmt.Sprintf("%#v", annots),
            "err": err,
        })

    return nestedError(errMsg, err)
}

func invalidFieldFilterError(field string) (error) {
    errMsg := wski18n.T(
        "Invalid field filter '{{.arg}}'.",
        map[string]interface{}{
            "arg": field,
        })

    return nonNestedError(errMsg)
}

func actionDeleteError(entityName string, err error) (error) {
    whisk.Debug(whisk.DbgError, "client.Actions.Delete(%s) error: %s\n", entityName, err)

    errMsg := wski18n.T(
        "Unable to delete action '{{.name}}': {{.err}}",
        map[string]interface{}{
            "name": entityName,
            "err": err,
        })

    return nestedError(errMsg, err)
}

func actionGetError(entityName string, err error) (error) {
    whisk.Debug(whisk.DbgError, "client.Actions.Get(%s) error: %s\n", entityName, err)

    errMsg := wski18n.T(
        "Unable to get action '{{.name}}': {{.err}}",
        map[string]interface{}{
            "name": entityName,
            "err": err,
        })

    return nestedError(errMsg, err)
}

func handleInvocationError(err error, entityName string, parameters interface{}) (error) {
    whisk.Debug(
        whisk.DbgError,
        "client.Actions.Invoke(%s, %s, %t) error: %s\n",
        entityName, parameters,
        flags.common.blocking,
        err)

    errMsg := wski18n.T(
        "Unable to invoke action '{{.name}}': {{.err}}",
        map[string]interface{}{
            "name": entityName,
            "err": err,
        })

    return nestedError(errMsg, err)
}

func actionListError(entityName string, options *whisk.ActionListOptions, err error) (error) {
    whisk.Debug(whisk.DbgError, "client.Actions.List(%s, %#v) error: %s\n", entityName, options, err)

    errMsg := wski18n.T(
        "Unable to obtain the list of actions for namespace '{{.name}}': {{.err}}",
        map[string]interface{}{
            "name": getClientNamespace(),
            "err": err,
        })

    return nestedError(errMsg, err)
}

func webInputError(arg string) (error) {
    errMsg := wski18n.T(
        "Invalid argument '{{.arg}}' for --web flag. Valid input consist of 'yes', 'true', 'raw', 'false', or 'no'.",
        map[string]interface{}{
            "arg": arg,
        })

    return nonNestedError(errMsg)
}

func zipKindError() (error) {
    errMsg := wski18n.T("creating an action from a .zip artifact requires specifying the action kind explicitly")

    return nonNestedError(errMsg)
}

func noArtifactError() (error) {
    errMsg := wski18n.T("An action name and code artifact are required.")

    return nonNestedError(errMsg)
}

func extensionError(extension string) (error) {
    errMsg := wski18n.T(
        "'{{.name}}' is not a supported action runtime",
        map[string]interface{}{
            "name": extension,
        })

    return nonNestedError(errMsg)
}

func javaEntryError() (error) {
    errMsg := wski18n.T("Java actions require --main to specify the fully-qualified name of the main class")

    return nonNestedError(errMsg)
}

func printActionCreated(entityName string) {
    fmt.Fprintf(
        color.Output,
        wski18n.T(
            "{{.ok}} created action {{.name}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "name": boldString(entityName),
            }))
}

func printActionUpdated(entityName string) {
    fmt.Fprintf(
        color.Output,
        wski18n.T(
            "{{.ok}} updated action {{.name}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "name": boldString(entityName),
            }))
}

func printBlockingTimeoutMsg(namespace string, entityName string, activationID interface{}) {
    fmt.Fprintf(
        colorable.NewColorableStderr(),
        wski18n.T(
            "{{.ok}} invoked /{{.namespace}}/{{.name}}, but the request has not yet finished, with id {{.id}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "namespace": boldString(namespace),
                "name": boldString(entityName),
                "id": boldString(activationID),
            }))
}

func printInvocationMsg(
    namespace string,
    entityName string,
    activationID interface{},
    response map[string]interface{},
    outputStream io.Writer) {
        if !flags.action.result {
            fmt.Fprintf(
                outputStream,
                wski18n.T(
                    "{{.ok}} invoked /{{.namespace}}/{{.name}} with id {{.id}}\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                        "namespace": boldString(namespace),
                        "name": boldString(entityName),
                        "id": boldString(activationID),
                    }))
        }

        if flags.common.blocking {
            printJSON(response, outputStream)
        }
}

func printActionGetWithField(entityName string, field string, action *whisk.Action) {
    fmt.Fprintf(
        color.Output,
        wski18n.T(
            "{{.ok}} got action {{.name}}, displaying field {{.field}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "name": boldString(entityName),
                "field": boldString(field),
            }))

    printField(action, field)
}

func printActionGet(entityName string, action *whisk.Action) {
    fmt.Fprintf(
        color.Output,
        wski18n.T("{{.ok}} got action {{.name}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "name": boldString(entityName),
            }))

    printJSON(action)
}

func printActionDeleted(entityName string) {
    fmt.Fprintf(
        color.Output,
        wski18n.T(
            "{{.ok}} deleted action {{.name}}\n",
            map[string]interface{}{
                "ok": color.GreenString("ok:"),
                "name": boldString(entityName),
            }))
}

// Check if the specified action is a web-action
func isWebAction(client *whisk.Client, qname QualifiedName) error {
    var err error = nil

    savedNs := client.Namespace
    client.Namespace = qname.namespace
    fullActionName := "/" + qname.namespace + "/" + qname.entityName

    action, _, err := client.Actions.Get(qname.entityName)
    if err != nil {
        whisk.Debug(whisk.DbgError, "client.Actions.Get(%s) error: %s\n", fullActionName, err)
        whisk.Debug(whisk.DbgError, "Unable to obtain action '%s' for web action validation\n", fullActionName)
        err = errors.New(wski18n.T("API action '{{.name}}' does not exist", map[string]interface{}{"name": fullActionName}))
    } else {
        err = errors.New(wski18n.T("API action '{{.name}}' is not a web action. Issue 'wsk action update {{.name}} --web true' to convert the action to a web action.",
            map[string]interface{}{"name": fullActionName}))
        weVal := getValue(action.Annotations, "web-export")
        if (weVal == nil) {
            whisk.Debug(whisk.DbgError, "getValue(annotations, web-export) for action %s found no value\n", fullActionName)
        } else {
            var webExport bool
            var ok bool
            if webExport, ok = weVal.(bool); !ok {
                whisk.Debug(whisk.DbgError, "web-export annotation value (%v) is not a boolean\n", weVal)
            } else if !webExport {
                whisk.Debug(whisk.DbgError, "web-export annotation value is false\n", weVal)
            } else {
                err = nil
            }
        }
    }

    client.Namespace = savedNs
    return err
}

func init() {
    actionCreateCmd.Flags().BoolVar(&flags.action.native, "native", false, wski18n.T("treat ACTION as native action (zip file provides a compatible executable to run)"))
    actionCreateCmd.Flags().StringVar(&flags.action.docker, "docker", "", wski18n.T("use provided docker image (a path on DockerHub) to run the action"))
    actionCreateCmd.Flags().BoolVar(&flags.action.copy, "copy", false, wski18n.T("treat ACTION as the name of an existing action"))
    actionCreateCmd.Flags().BoolVar(&flags.action.sequence, "sequence", false, wski18n.T("treat ACTION as comma separated sequence of actions to invoke"))
    actionCreateCmd.Flags().StringVar(&flags.action.kind, "kind", "", wski18n.T("the `KIND` of the action runtime (example: swift:default, nodejs:default)"))
    actionCreateCmd.Flags().StringVar(&flags.action.main, "main", "", wski18n.T("the name of the action entry point (function or fully-qualified method name when applicable)"))
    actionCreateCmd.Flags().IntVarP(&flags.action.timeout, "timeout", "t", TIMEOUT_LIMIT, wski18n.T("the timeout `LIMIT` in milliseconds after which the action is terminated"))
    actionCreateCmd.Flags().IntVarP(&flags.action.memory, "memory", "m", MEMORY_LIMIT, wski18n.T("the maximum memory `LIMIT` in MB for the action"))
    actionCreateCmd.Flags().IntVarP(&flags.action.logsize, "logsize", "l", LOGSIZE_LIMIT, wski18n.T("the maximum log size `LIMIT` in MB for the action"))
    actionCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", nil, wski18n.T("annotation values in `KEY VALUE` format"))
    actionCreateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
    actionCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", nil, wski18n.T("parameter values in `KEY VALUE` format"))
    actionCreateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
    actionCreateCmd.Flags().StringVar(&flags.action.web, "web", "", wski18n.T("treat ACTION as a web action, a raw HTTP web action, or as a standard action; yes | true = web action, raw = raw HTTP web action, no | false = standard action"))

    actionUpdateCmd.Flags().BoolVar(&flags.action.native, "native", false, wski18n.T("treat ACTION as native action (zip file provides a compatible executable to run)"))
    actionUpdateCmd.Flags().StringVar(&flags.action.docker, "docker", "", wski18n.T("use provided docker image (a path on DockerHub) to run the action"))
    actionUpdateCmd.Flags().BoolVar(&flags.action.copy, "copy", false, wski18n.T("treat ACTION as the name of an existing action"))
    actionUpdateCmd.Flags().BoolVar(&flags.action.sequence, "sequence", false, wski18n.T("treat ACTION as comma separated sequence of actions to invoke"))
    actionUpdateCmd.Flags().StringVar(&flags.action.kind, "kind", "", wski18n.T("the `KIND` of the action runtime (example: swift:default, nodejs:default)"))
    actionUpdateCmd.Flags().StringVar(&flags.action.main, "main", "", wski18n.T("the name of the action entry point (function or fully-qualified method name when applicable)"))
    actionUpdateCmd.Flags().IntVarP(&flags.action.timeout, "timeout", "t", TIMEOUT_LIMIT, wski18n.T("the timeout `LIMIT` in milliseconds after which the action is terminated"))
    actionUpdateCmd.Flags().IntVarP(&flags.action.memory, "memory", "m", MEMORY_LIMIT, wski18n.T("the maximum memory `LIMIT` in MB for the action"))
    actionUpdateCmd.Flags().IntVarP(&flags.action.logsize, "logsize", "l", LOGSIZE_LIMIT, wski18n.T("the maximum log size `LIMIT` in MB for the action"))
    actionUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
    actionUpdateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
    actionUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
    actionUpdateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
    actionUpdateCmd.Flags().StringVar(&flags.action.web, "web", "", wski18n.T("treat ACTION as a web action, a raw HTTP web action, or as a standard action; yes | true = web action, raw = raw HTTP web action, no | false = standard action"))

    actionInvokeCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
    actionInvokeCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
    actionInvokeCmd.Flags().BoolVarP(&flags.common.blocking, "blocking", "b", false, wski18n.T("blocking invoke"))
    actionInvokeCmd.Flags().BoolVarP(&flags.action.result, "result", "r", false, wski18n.T("blocking invoke; show only activation result (unless there is a failure)"))

    actionGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, wski18n.T("summarize action details"))

    actionListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of actions from the result"))
    actionListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of actions from the collection"))

    actionCmd.AddCommand(
        actionCreateCmd,
        actionUpdateCmd,
        actionInvokeCmd,
        actionGetCmd,
        actionDeleteCmd,
        actionListCmd,
    )
}
