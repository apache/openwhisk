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
    "bytes"
    "bufio"
    "errors"
    "fmt"
    "reflect"
    "strconv"
    "strings"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/fatih/color"
    "github.com/ghodss/yaml"
    "github.com/spf13/cobra"
    "encoding/json"
)

const (
    yamlFileExtension = "yaml"
    ymlFileExtension = "yml"

    formatOptionYaml = "yaml"
    formatOptionJson = "json"
)

var apiCmd = &cobra.Command{
    Use:   "api",
    Short: wski18n.T("work with APIs"),
}

var fmtString = "%-30s %7s %20s  %s\n"

func IsValidApiVerb(verb string) (error, bool) {
    // Is the API verb valid?
    if _, ok := whisk.ApiVerbs[strings.ToUpper(verb)]; !ok {
        whisk.Debug(whisk.DbgError, "Invalid API verb: '%s'\n", verb)
        errMsg := wski18n.T("'{{.verb}}' is not a valid API verb.  Valid values are: {{.verbs}}",
                map[string]interface{}{
                    "verb": verb,
                    "verbs": reflect.ValueOf(whisk.ApiVerbs).MapKeys()})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr, false
    }
    return nil, true
}

func hasPathPrefix(path string) (error, bool) {
    if (! strings.HasPrefix(path, "/")) {
        whisk.Debug(whisk.DbgError, "path does not begin with '/': '%s'\n", path)
        errMsg := wski18n.T("'{{.path}}' must begin with '/'.",
                map[string]interface{}{
                    "path": path,
                })
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr, false
    }
    return nil, true
}

func isValidBasepath(basepath string) (error, bool) {
    if whiskerr, ok := hasPathPrefix(basepath); !ok {
        return whiskerr, false
    }
    return nil, true
}

func isValidRelpath(relpath string) (error, bool) {
    if whiskerr, ok := hasPathPrefix(relpath); !ok {
        return whiskerr, false
    }
    return nil, true
}

/*
 * Pull the managedUrl (external API URL) from the API configuration
 */
func getManagedUrl(api *whisk.RetApi, relpath string, operation string) (url string) {
    baseUrl := strings.TrimSuffix(api.BaseUrl, "/")
    whisk.Debug(whisk.DbgInfo, "getManagedUrl: baseUrl = '%s', relpath = '%s', operation = '%s'\n", baseUrl, relpath, operation)
    for path, _ := range api.Swagger.Paths {
        whisk.Debug(whisk.DbgInfo, "getManagedUrl: comparing api relpath: '%s'\n", path)
        if (path == relpath) {
            whisk.Debug(whisk.DbgInfo, "getManagedUrl: relpath matches '%s'\n", relpath)
            for op, _  := range api.Swagger.Paths[path] {
                whisk.Debug(whisk.DbgInfo, "getManagedUrl: comparing operation: '%s'\n", op)
                if (strings.ToLower(op) == strings.ToLower(operation)) {
                    whisk.Debug(whisk.DbgInfo, "getManagedUrl: operation matches: '%s'\n", operation)
                    url = baseUrl+path
                }
            }
        }
    }
    return url
}

//////////////
// Commands //
//////////////
var apiCreateCmd = &cobra.Command{
    Use:           "create ([BASE_PATH] API_PATH API_VERB ACTION] | --config-file CFG_FILE) ",
    Short:         wski18n.T("create a new API"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var api *whisk.Api
        var err error
        var qname = new(QualifiedName)

        if (len(args) == 0 && flags.api.configfile == "") {
            whisk.Debug(whisk.DbgError, "No swagger file and no arguments\n")
            errMsg := wski18n.T("Invalid argument(s). Specify a swagger file or specify an API base path with an API path, an API verb, and an action name.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return whiskErr
        } else if (len(args) == 0 && flags.api.configfile != "") {
            api, err = parseSwaggerApi()
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseSwaggerApi() error: %s\n", err)
                errMsg := wski18n.T("Unable to parse swagger file: {{.err}}", map[string]interface{}{"err": err})
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }
        } else {
            if whiskErr := CheckArgs(args, 3, 4, "Api create",
                wski18n.T("Specify a swagger file or specify an API base path with an API path, an API verb, and an action name.")); whiskErr != nil {
                return whiskErr
            }
            api, qname, err = parseApi(cmd, args)
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseApi(%s, %s) error: %s\n", cmd, args, err)
                errMsg := wski18n.T("Unable to parse api command arguments: {{.err}}",
                    map[string]interface{}{"err": err})
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }

            // Confirm that the specified action is a web-action
            err = isWebAction(Client, *qname)
            if err != nil {
                whisk.Debug(whisk.DbgError, "isWebAction(%v) is false: %s\n", qname, err)
                whiskErr := whisk.MakeWskError(err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }
        }

        apiCreateReq := new(whisk.ApiCreateRequest)
        apiCreateReq.ApiDoc = api

        apiCreateReqOptions := new(whisk.ApiCreateRequestOptions)
        if apiCreateReqOptions.SpaceGuid, err = getUserContextId(); err != nil {
            return err
        }
        if apiCreateReqOptions.AccessToken, err = getAccessToken(); err != nil {
            return err
        }
        apiCreateReqOptions.ResponseType = flags.api.resptype
        whisk.Debug(whisk.DbgInfo, "AccessToken: %s\nSpaceGuid: %s\nResponsType: %s",
            apiCreateReqOptions.AccessToken, apiCreateReqOptions.SpaceGuid, apiCreateReqOptions.ResponseType)

        retApi, _, err := Client.Apis.Insert(apiCreateReq, apiCreateReqOptions, whisk.DoNotOverwrite)
        if err != nil {
            whisk.Debug(whisk.DbgError, "Client.Apis.Insert(%#v, false) error: %s\n", api, err)
            errMsg := wski18n.T("Unable to create API: {{.err}}", map[string]interface{}{"err": err})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        if (api.Swagger == "") {
            baseUrl := retApi.BaseUrl
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} created API {{.path}} {{.verb}} for action {{.name}}\n{{.fullpath}}\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                        "path": strings.TrimSuffix(api.GatewayBasePath, "/")+api.GatewayRelPath,
                        "verb": api.GatewayMethod,
                        "name": boldString("/"+api.Action.Namespace+"/"+api.Action.Name),
                        "fullpath": strings.TrimSuffix(baseUrl, "/")+api.GatewayRelPath,
                    }))
        } else {
            whisk.Debug(whisk.DbgInfo, "Processing swagger based create API response\n")
            baseUrl := retApi.BaseUrl
            for path, _ := range retApi.Swagger.Paths {
                managedUrl := strings.TrimSuffix(baseUrl, "/")+path
                whisk.Debug(whisk.DbgInfo, "Managed path: '%s'\n",managedUrl)
                for op, opv  := range retApi.Swagger.Paths[path] {
                    whisk.Debug(whisk.DbgInfo, "Path operation: '%s'\n", op)
                    var fqActionName string
                    if (opv.XOpenWhisk == nil) {
                        fqActionName = ""
                    } else if (len(opv.XOpenWhisk.Package) > 0) {
                        fqActionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.Package+"/"+opv.XOpenWhisk.ActionName
                    } else {
                        fqActionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.ActionName
                    }

                    whisk.Debug(whisk.DbgInfo, "baseUrl '%s'  Path '%s'  Path obj %+v\n", baseUrl, path, opv)
                    if len(fqActionName) > 0 {
                        fmt.Fprintf(color.Output,
                            wski18n.T("{{.ok}} created API {{.path}} {{.verb}} for action {{.name}}\n{{.fullpath}}\n",
                                map[string]interface{}{
                                    "ok": color.GreenString("ok:"),
                                    "path": strings.TrimSuffix(retApi.Swagger.BasePath, "/") + path,
                                    "verb": op,
                                    "name": boldString(fqActionName),
                                    "fullpath": managedUrl,
                                }))
                    } else {
                        fmt.Fprintf(color.Output,
                            wski18n.T("{{.ok}} created API {{.path}} {{.verb}}\n{{.fullpath}}\n",
                                map[string]interface{}{
                                    "ok": color.GreenString("ok:"),
                                    "path": strings.TrimSuffix(retApi.Swagger.BasePath, "/") + path,
                                    "verb": op,
                                    "fullpath": managedUrl,
                                }))
                    }
                }
            }
        }


        return nil
    },
}

var apiGetCmd = &cobra.Command{
    Use:           "get BASE_PATH | API_NAME",
    Short:         wski18n.T("get API details"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var isBasePathArg bool = true

        if whiskErr := CheckArgs(args, 1, 1, "Api get",
            wski18n.T("An API base path or API name is required.")); whiskErr != nil {
            return whiskErr
        }

        if ( cmd.LocalFlags().Changed("format") &&
             strings.ToLower(flags.common.format) != formatOptionYaml &&
             strings.ToLower(flags.common.format) != formatOptionJson) {
            errMsg := wski18n.T("Invalid format type: {{.type}}", map[string]interface{}{"type": flags.common.format})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        apiGetReq := new(whisk.ApiGetRequest)
        apiGetReqOptions := new(whisk.ApiGetRequestOptions)
        apiGetReqOptions.ApiBasePath = args[0]
        if apiGetReqOptions.SpaceGuid, err = getUserContextId(); err != nil {
            return err
        }
        if apiGetReqOptions.AccessToken, err = getAccessToken(); err != nil {
            return err
        }

        retApi, _, err := Client.Apis.Get(apiGetReq, apiGetReqOptions)
        if err != nil {
            whisk.Debug(whisk.DbgError, "Client.Apis.Get(%#v, %#v) error: %s\n", apiGetReq, apiGetReqOptions, err)
            errMsg := wski18n.T("Unable to get API '{{.name}}': {{.err}}", map[string]interface{}{"name": args[0], "err": err})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }
        whisk.Debug(whisk.DbgInfo, "Client.Apis.Get returned: %#v\n", retApi)

        var displayResult interface{} = nil
        if (flags.common.detail) {
            if (retApi.Apis != nil && len(retApi.Apis) > 0 &&
            retApi.Apis[0].ApiValue != nil) {
                displayResult = retApi.Apis[0].ApiValue
            } else {
                whisk.Debug(whisk.DbgError, "No result object returned\n")
            }
        } else {
            if (retApi.Apis != nil && len(retApi.Apis) > 0 &&
            retApi.Apis[0].ApiValue != nil &&
            retApi.Apis[0].ApiValue.Swagger != nil) {
                displayResult = retApi.Apis[0].ApiValue.Swagger
            } else {
                whisk.Debug(whisk.DbgError, "No swagger returned\n")
            }
        }
        if (displayResult == nil) {
            var errMsg string
            if (isBasePathArg) {
                errMsg = wski18n.T("API does not exist for basepath {{.basepath}}",
                    map[string]interface{}{"basepath": args[0]})
            } else {
                errMsg = wski18n.T("API does not exist for API name {{.apiname}}",
                    map[string]interface{}{"apiname": args[0]})
            }

            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        if ( cmd.LocalFlags().Changed("format") && strings.ToLower(flags.common.format) == formatOptionYaml ) {
            var jsonOutputBuffer bytes.Buffer
            var jsonOutputWriter = bufio.NewWriter(&jsonOutputBuffer)
            printJSON(displayResult, jsonOutputWriter)
            jsonOutputWriter.Flush()
            yamlbytes, err := yaml.JSONToYAML(jsonOutputBuffer.Bytes())
            if err != nil {
                whisk.Debug(whisk.DbgError, "yaml.JSONToYAML() error: %s\n", err)
                errMsg := wski18n.T("Unable to convert API into YAML: {{.err}}", map[string]interface{}{"err": err})
                whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
            fmt.Println(string(yamlbytes))
        } else {
            printJSON(displayResult)
        }

        return nil
    },
}

var apiDeleteCmd = &cobra.Command{
    Use:           "delete BASE_PATH | API_NAME [API_PATH [API_VERB]]",
    Short:         wski18n.T("delete an API"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := CheckArgs(args, 1, 3, "Api delete",
            wski18n.T("An API base path or API name is required.  An optional API relative path and operation may also be provided.")); whiskErr != nil {
            return whiskErr
        }

        apiDeleteReq := new(whisk.ApiDeleteRequest)
        apiDeleteReqOptions := new(whisk.ApiDeleteRequestOptions)
        if apiDeleteReqOptions.SpaceGuid, err = getUserContextId(); err != nil {
            return err
        }
        if apiDeleteReqOptions.AccessToken, err = getAccessToken(); err != nil {
            return err
        }

        // Is the argument a basepath (must start with /) or an API name
        if _, ok := isValidBasepath(args[0]); !ok {
            whisk.Debug(whisk.DbgInfo, "Treating '%s' as an API name; as it does not begin with '/'\n", args[0])
            apiDeleteReqOptions.ApiBasePath = args[0]
        } else {
            apiDeleteReqOptions.ApiBasePath = args[0]
        }

        if (len(args) > 1) {
            // Is the API path valid?
            if whiskErr, ok := isValidRelpath(args[1]); !ok {
                return whiskErr
            }
            apiDeleteReqOptions.ApiRelPath = args[1]
        }
        if (len(args) > 2) {
            // Is the API verb valid?
            if whiskErr, ok := IsValidApiVerb(args[2]); !ok {
                return whiskErr
            }
            apiDeleteReqOptions.ApiVerb = strings.ToUpper(args[2])
        }

        _, err = Client.Apis.Delete(apiDeleteReq, apiDeleteReqOptions)
        if err != nil {
            whisk.Debug(whisk.DbgError, "Client.Apis.Delete(%#v, %#v) error: %s\n", apiDeleteReq, apiDeleteReqOptions, err)
            errMsg := wski18n.T("Unable to delete API: {{.err}}", map[string]interface{}{"err": err})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        if (len(args) == 1) {
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} deleted API {{.basepath}}\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                        "basepath": apiDeleteReqOptions.ApiBasePath,
                    }))
        } else if (len(args) == 2 ) {
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} deleted {{.path}} from {{.basepath}}\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                        "path": apiDeleteReqOptions.ApiRelPath,
                        "basepath": apiDeleteReqOptions.ApiBasePath,
                    }))
        } else {
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} deleted {{.path}} {{.verb}} from {{.basepath}}\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                        "path": apiDeleteReqOptions.ApiRelPath,
                        "verb": apiDeleteReqOptions.ApiVerb,
                        "basepath": apiDeleteReqOptions.ApiBasePath,
                    }))
        }

        return nil
    },
}

var apiListCmd = &cobra.Command{
    Use:           "list [[BASE_PATH | API_NAME] [API_PATH [API_VERB]]",
    Short:         wski18n.T("list APIs"),
    SilenceUsage:  true,
    SilenceErrors: true,
    PreRunE:       SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var retApiList *whisk.ApiListResponse
        var retApi *whisk.ApiGetResponse
        var retApiArray *whisk.RetApiArray
        var apiPath string
        var apiVerb string
        var orderFilteredList []whisk.ApiFilteredList
        var orderFilteredRow []whisk.ApiFilteredRow

        if whiskErr := CheckArgs(args, 0, 3, "Api list",
            wski18n.T("Optional parameters are: API base path (or API name), API relative path and operation.")); whiskErr != nil {
            return whiskErr
        }

        if (len(args) == 0) {
            // List API request query parameters
            apiListReqOptions := new(whisk.ApiListRequestOptions)
            apiListReqOptions.Limit = flags.common.limit
            apiListReqOptions.Skip = flags.common.skip
            if apiListReqOptions.SpaceGuid, err = getUserContextId(); err != nil {
                return err
            }
            if apiListReqOptions.AccessToken, err = getAccessToken(); err != nil {
                return err
            }

            retApiList, _, err = Client.Apis.List(apiListReqOptions)
            if err != nil {
                whisk.Debug(whisk.DbgError, "Client.Apis.List(%#v) error: %s\n", apiListReqOptions, err)
                errMsg := wski18n.T("Unable to obtain the API list: {{.err}}", map[string]interface{}{"err": err})
                whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
            whisk.Debug(whisk.DbgInfo, "Client.Apis.List returned: %#v (%+v)\n", retApiList, retApiList)
            // Cast to a common type to allow for code to print out apilist response or apiget response
            retApiArray = (*whisk.RetApiArray)(retApiList)
        } else {
            // Get API request body
            apiGetReq := new(whisk.ApiGetRequest)
            apiGetReq.Namespace = Client.Config.Namespace
            // Get API request options
            apiGetReqOptions := new(whisk.ApiGetRequestOptions)
            if apiGetReqOptions.SpaceGuid, err = getUserContextId(); err != nil {
                return err
            }
            if apiGetReqOptions.AccessToken, err = getAccessToken(); err != nil {
                return err
            }

            // The first argument is either a basepath (must start with /) or an API name
            apiGetReqOptions.ApiBasePath = args[0]
            if (len(args) > 1) {
                // Is the API path valid?
                if whiskErr, ok := isValidRelpath(args[1]); !ok {
                    return whiskErr
                }
                apiPath = args[1]
                apiGetReqOptions.ApiRelPath = apiPath
            }
            if (len(args) > 2) {
                // Is the API verb valid?
                if whiskErr, ok := IsValidApiVerb(args[2]); !ok {
                    return whiskErr
                }
                apiVerb = strings.ToUpper(args[2])
                apiGetReqOptions.ApiVerb = apiVerb
            }

            retApi, _, err = Client.Apis.Get(apiGetReq, apiGetReqOptions)
            if err != nil {
                whisk.Debug(whisk.DbgError, "Client.Apis.Get(%#v, %#v) error: %s\n", apiGetReq, apiGetReqOptions, err)
                errMsg := wski18n.T("Unable to obtain the API list: {{.err}}", map[string]interface{}{"err": err})
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
            whisk.Debug(whisk.DbgInfo, "Client.Apis.Get returned: %#v\n", retApi)
            // Cast to a common type to allow for code to print out apilist response or apiget response
            retApiArray = (*whisk.RetApiArray)(retApi)
        }
        //Checks for any order flags being passed
        sortByName := flags.common.nameSort
        // Display the APIs - applying any specified filtering
        if (flags.common.full) {
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} APIs\n",
                    map[string]interface{}{
                        "ok": color.GreenString("ok:"),
                    }))
            for i := 0; i < len(retApiArray.Apis); i++ {
                orderFilteredList = append(orderFilteredList, genFilteredList(retApiArray.Apis[i].ApiValue, apiPath, apiVerb)...)
            }
            printList(orderFilteredList, sortByName)  // Sends an array of structs that contains specifed variables that are not truncated
        } else {
            if (len(retApiArray.Apis) > 0) {
                // Dynamically create the output format string based on the maximum size of the
                // fully qualified action name and the API Name.
                maxActionNameSize := min(40, max(len("Action"), getLargestActionNameSize(retApiArray, apiPath, apiVerb)))
                maxApiNameSize := min(30, max(len("API Name"), getLargestApiNameSize(retApiArray, apiPath, apiVerb)))
                fmtString = "%-"+strconv.Itoa(maxActionNameSize)+"s %7s %"+strconv.Itoa(maxApiNameSize+1)+"s  %s\n"
                fmt.Fprintf(color.Output,
                    wski18n.T("{{.ok}} APIs\n",
                        map[string]interface{}{
                            "ok": color.GreenString("ok:"),
                        }))
                for i := 0; i < len(retApiArray.Apis); i++ {
                    orderFilteredRow = append(orderFilteredRow, genFilteredRow(retApiArray.Apis[i].ApiValue, apiPath, apiVerb, maxActionNameSize, maxApiNameSize)...)
                }
                printList(orderFilteredRow, sortByName)  // Sends an array of structs that contains specifed variables that are truncated
            } else {
                fmt.Fprintf(color.Output,
                    wski18n.T("{{.ok}} APIs\n",
                        map[string]interface{}{
                            "ok": color.GreenString("ok:"),
                        }))
                printList(orderFilteredRow, sortByName)  // Sends empty orderFilteredRow so that defaultHeader can be printed
            }
        }

        return nil
    },
}

// genFilteredList(resultApi, api) generates an array of
//      ApiFilteredLists for the purpose of ordering and printing in a list form.
//      NOTE: genFilteredRow() generates entries with one line per configuration
//         property (action name, verb, api name, api gw url)
func genFilteredList(resultApi *whisk.RetApi, apiPath string, apiVerb string) []whisk.ApiFilteredList{
    var orderInfo whisk.ApiFilteredList
    var orderInfoArr []whisk.ApiFilteredList
    baseUrl := strings.TrimSuffix(resultApi.BaseUrl, "/")
    apiName := resultApi.Swagger.Info.Title
    basePath := resultApi.Swagger.BasePath
    if (resultApi.Swagger != nil && resultApi.Swagger.Paths != nil) {
        for path, _ := range resultApi.Swagger.Paths {
            whisk.Debug(whisk.DbgInfo, "genFilteredApi: comparing api relpath: '%s'\n", path)
            if ( len(apiPath) == 0 || path == apiPath) {
                whisk.Debug(whisk.DbgInfo, "genFilteredList: relpath matches\n")
                for op, opv  := range resultApi.Swagger.Paths[path] {
                    whisk.Debug(whisk.DbgInfo, "genFilteredList: comparing operation: '%s'\n", op)
                    if ( len(apiVerb) == 0 || strings.ToLower(op) == strings.ToLower(apiVerb)) {
                        whisk.Debug(whisk.DbgInfo, "genFilteredList: operation matches: %#v\n", opv)
                        var actionName string
                        if (opv.XOpenWhisk == nil) {
                            actionName = ""
                        } else if (len(opv.XOpenWhisk.Package) > 0) {
                            actionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.Package+"/"+opv.XOpenWhisk.ActionName
                        } else {
                            actionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.ActionName
                        }
                        orderInfo = AssignListInfo(actionName, op, apiName, basePath, path, baseUrl+path)
                        whisk.Debug(whisk.DbgInfo, "Appening to orderInfoArr: %s %s\n", orderInfo.RelPath)
                        orderInfoArr = append(orderInfoArr, orderInfo)
                    }
                }
            }
        }
    }
    return orderInfoArr
}

// genFilteredRow(resultApi, api, maxApiNameSize, maxApiNameSize) generates an array of
//      ApiFilteredRows for the purpose of ordering and printing in a list form by parsing and
//      initializing vaules for each individual ApiFilteredRow struct.
//      NOTE: Large action and api name values will be truncated by their associated max size parameters.
func genFilteredRow(resultApi *whisk.RetApi, apiPath string, apiVerb string, maxActionNameSize int, maxApiNameSize int) []whisk.ApiFilteredRow {
    var orderInfo whisk.ApiFilteredRow
    var orderInfoArr []whisk.ApiFilteredRow
    baseUrl := strings.TrimSuffix(resultApi.BaseUrl, "/")
    apiName := resultApi.Swagger.Info.Title
    basePath := resultApi.Swagger.BasePath
    if (resultApi.Swagger != nil && resultApi.Swagger.Paths != nil) {
        for path, _ := range resultApi.Swagger.Paths {
            whisk.Debug(whisk.DbgInfo, "genFilteredRow: comparing api relpath: '%s'\n", path)
            if ( len(apiPath) == 0 || path == apiPath) {
                whisk.Debug(whisk.DbgInfo, "genFilteredRow: relpath matches\n")
                for op, opv  := range resultApi.Swagger.Paths[path] {
                    whisk.Debug(whisk.DbgInfo, "genFilteredRow: comparing operation: '%s'\n", op)
                    if ( len(apiVerb) == 0 || strings.ToLower(op) == strings.ToLower(apiVerb)) {
                        whisk.Debug(whisk.DbgInfo, "genFilteredRow: operation matches: %#v\n", opv)
                        var actionName string
                        if (opv.XOpenWhisk == nil) {
                            actionName = ""
                        } else if (len(opv.XOpenWhisk.Package) > 0) {
                            actionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.Package+"/"+opv.XOpenWhisk.ActionName
                        } else {
                            actionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.ActionName
                        }
                        orderInfo = AssignRowInfo(actionName[0 : min(len(actionName), maxActionNameSize)], op, apiName[0 : min(len(apiName), maxApiNameSize)], basePath, path, baseUrl+path)
                        orderInfo.FmtString = fmtString
                        whisk.Debug(whisk.DbgInfo, "Appening to orderInfoArr: %s %s\n", orderInfo.RelPath)
                        orderInfoArr = append(orderInfoArr, orderInfo)
                    }
                }
            }
        }
    }
    return orderInfoArr
}

// AssignRowInfo(actionName, verb, apiName, basePath, relPath, url) assigns
//      the given vaules to and initializes an ApiFilteredRow struct, then returns it.
func AssignRowInfo(actionName string, verb string, apiName string, basePath string, relPath string, url string) whisk.ApiFilteredRow {
    var orderInfo whisk.ApiFilteredRow

    orderInfo.ActionName = actionName
    orderInfo.Verb = verb
    orderInfo.ApiName = apiName
    orderInfo.BasePath = basePath
    orderInfo.RelPath = relPath
    orderInfo.Url = url

    return orderInfo
}

// AssignListInfo(actionName, verb, apiName, basePath, relPath, url) assigns
//      the given vaules to and initializes an ApiFilteredList struct, then returns it.
func AssignListInfo(actionName string, verb string, apiName string, basePath string, relPath string, url string) whisk.ApiFilteredList {
    var orderInfo whisk.ApiFilteredList

    orderInfo.ActionName = actionName
    orderInfo.Verb = verb
    orderInfo.ApiName = apiName
    orderInfo.BasePath = basePath
    orderInfo.RelPath = relPath
    orderInfo.Url = url

    return orderInfo
}

func getLargestActionNameSize(retApiArray *whisk.RetApiArray, apiPath string, apiVerb string) int {
    var maxNameSize = 0
    for i := 0; i < len(retApiArray.Apis); i++ {
        var resultApi = retApiArray.Apis[i].ApiValue
        if (resultApi.Swagger != nil && resultApi.Swagger.Paths != nil) {
            for path, _ := range resultApi.Swagger.Paths {
                whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: comparing api relpath: '%s'\n", path)
                if ( len(apiPath) == 0 || path == apiPath) {
                    whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: relpath matches\n")
                    for op, opv  := range resultApi.Swagger.Paths[path] {
                        whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: comparing operation: '%s'\n", op)
                        if ( len(apiVerb) == 0 || strings.ToLower(op) == strings.ToLower(apiVerb)) {
                            whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: operation matches: %#v\n", opv)
                            var fullActionName string
                            if (opv.XOpenWhisk == nil) {
                                fullActionName = ""
                            } else if (len(opv.XOpenWhisk.Package) > 0) {
                                fullActionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.Package+"/"+opv.XOpenWhisk.ActionName
                            } else {
                                fullActionName = "/"+opv.XOpenWhisk.Namespace+"/"+opv.XOpenWhisk.ActionName
                            }
                            if (len(fullActionName) > maxNameSize) {
                                maxNameSize = len(fullActionName)
                            }
                        }
                    }
                }
            }
        }
    }
    return maxNameSize
}

func getLargestApiNameSize(retApiArray *whisk.RetApiArray, apiPath string, apiVerb string) int {
    var maxNameSize = 0
    for i := 0; i < len(retApiArray.Apis); i++ {
        var resultApi = retApiArray.Apis[i].ApiValue
        apiName := resultApi.Swagger.Info.Title
        if (resultApi.Swagger != nil && resultApi.Swagger.Paths != nil) {
            for path, _ := range resultApi.Swagger.Paths {
                whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: comparing api relpath: '%s'\n", path)
                if ( len(apiPath) == 0 || path == apiPath) {
                    whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: relpath matches\n")
                    for op, opv  := range resultApi.Swagger.Paths[path] {
                        whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: comparing operation: '%s'\n", op)
                        if ( len(apiVerb) == 0 || strings.ToLower(op) == strings.ToLower(apiVerb)) {
                            whisk.Debug(whisk.DbgInfo, "getLargestActionNameSize: operation matches: %#v\n", opv)
                            if (len(apiName) > maxNameSize) {
                                maxNameSize = len(apiName)
                            }
                        }
                    }
                }
            }
        }
    }
    return maxNameSize
}

/*
 * if # args = 4
 * args[0] = API base path
 * args[0] = API relative path
 * args[1] = API verb
 * args[2] = Optional.  Action name (may or may not be qualified with namespace and package name)
 *
 * if # args = 3
 * args[0] = API relative path
 * args[1] = API verb
 * args[2] = Optional.  Action name (may or may not be qualified with namespace and package name)
 */
func parseApi(cmd *cobra.Command, args []string) (*whisk.Api, *QualifiedName, error) {
    var err error
    var basepath string = "/"
    var apiname string
    var basepathArgIsApiName = false;

    api := new(whisk.Api)

    if (len(args) > 3) {
        // Is the argument a basepath (must start with /) or an API name
        if _, ok := isValidBasepath(args[0]); !ok {
            whisk.Debug(whisk.DbgInfo, "Treating '%s' as an API name; as it does not begin with '/'\n", args[0])
            basepathArgIsApiName = true;
        }
        basepath = args[0]

        // Shift the args so the remaining code works with or without the explicit base path arg
        args = args[1:]
    }

    // Is the API path valid?
    if (len(args) > 0) {
        if whiskErr, ok := isValidRelpath(args[0]); !ok {
            return nil, nil, whiskErr
        }
        api.GatewayRelPath = args[0]    // Maintain case as URLs may be case-sensitive
    }

    // Is the API verb valid?
    if (len(args) > 1) {
        if whiskErr, ok := IsValidApiVerb(args[1]); !ok {
            return nil, nil, whiskErr
        }
        api.GatewayMethod = strings.ToUpper(args[1])
    }

    // Is the specified action name valid?
    var qName = new(QualifiedName)
    if (len(args) == 3) {
        qName, err = NewQualifiedName(args[2])
        if err != nil {
            whisk.Debug(whisk.DbgError, "NewQualifiedName(%s) failed: %s\n", args[2], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid action name: {{.err}}",
                map[string]interface{}{"name": args[2], "err": err})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, nil, whiskErr
        }
        if (qName.GetEntityName() == "") {
            whisk.Debug(whisk.DbgError, "Action name '%s' is invalid\n", args[2])
            errMsg := wski18n.T("'{{.name}}' is not a valid action name.", map[string]interface{}{"name": args[2]})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, nil, whiskErr
        }
    }

    if ( len(flags.api.apiname) > 0 ) {
        if (basepathArgIsApiName) {
            // Specifying API name as argument AND as a --apiname option value is invalid
            whisk.Debug(whisk.DbgError, "API is specified as an argument '%s' and as a flag '%s'\n", basepath, flags.api.apiname)
            errMsg := wski18n.T("An API name can only be specified once.")
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, nil, whiskErr
        }
        apiname = flags.api.apiname
    }

    api.Namespace = Client.Config.Namespace
    api.Action = new(whisk.ApiAction)
    var urlActionPackage string
    if (len(qName.GetPackageName()) > 0) {
        urlActionPackage = qName.GetPackageName()
    } else {
        urlActionPackage = "default"
    }
    api.Action.BackendUrl = "https://" + Client.Config.Host + "/api/v1/web/" + qName.GetNamespace() + "/" + urlActionPackage + "/" + qName.GetEntity() + ".http"
    api.Action.BackendMethod = api.GatewayMethod
    api.Action.Name = qName.GetEntityName()
    api.Action.Namespace = qName.GetNamespace()
    api.Action.Auth = Client.Config.AuthToken
    api.ApiName = apiname
    api.GatewayBasePath = basepath
    if (!basepathArgIsApiName) { api.Id = "API:"+api.Namespace+":"+api.GatewayBasePath }

    whisk.Debug(whisk.DbgInfo, "Parsed api struct: %#v\n", api)
    return api, qName, nil
}

func parseSwaggerApi() (*whisk.Api, error) {
    // Test is for completeness, but this situation should only arise due to an internal error
    if ( len(flags.api.configfile) == 0 ) {
        whisk.Debug(whisk.DbgError, "No swagger file is specified\n")
        errMsg := wski18n.T("A configuration file was not specified.")
        whiskErr := whisk.MakeWskError(errors.New(errMsg),whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }

    swagger, err:= readFile(flags.api.configfile)
    if ( err != nil ) {
        whisk.Debug(whisk.DbgError, "readFile(%s) error: %s\n", flags.api.configfile, err)
        errMsg := wski18n.T("Error reading swagger file '{{.name}}': {{.err}}",
            map[string]interface{}{"name": flags.api.configfile, "err": err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }

    // Check if this swagger is in JSON or YAML format
    isYaml := strings.HasSuffix(flags.api.configfile, yamlFileExtension) || strings.HasSuffix(flags.api.configfile, ymlFileExtension)
    if isYaml {
        whisk.Debug(whisk.DbgInfo, "Converting YAML formated API configuration into JSON\n")
        jsonbytes, err := yaml.YAMLToJSON([]byte(swagger))
        if err != nil {
            whisk.Debug(whisk.DbgError, "yaml.YAMLToJSON() error: %s\n", err)
            errMsg := wski18n.T("Unable to parse YAML configuration file: {{.err}}", map[string]interface{}{"err": err})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return nil, whiskErr
        }
        swagger = string(jsonbytes)
    }

    // Parse the JSON into a swagger object
    swaggerObj := new(whisk.ApiSwagger)
    err = json.Unmarshal([]byte(swagger), swaggerObj)
    if ( err != nil ) {
        whisk.Debug(whisk.DbgError, "JSON parse of '%s' error: %s\n", flags.api.configfile, err)
        errMsg := wski18n.T("Error parsing swagger file '{{.name}}': {{.err}}",
            map[string]interface{}{"name": flags.api.configfile, "err": err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }
    if (swaggerObj.BasePath == "" || swaggerObj.SwaggerName == "" || swaggerObj.Info == nil || swaggerObj.Paths == nil) {
        whisk.Debug(whisk.DbgError, "Swagger file is invalid.\n", flags.api.configfile, err)
        errMsg := wski18n.T("Swagger file is invalid (missing basePath, info, paths, or swagger fields)")
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }
    if _, ok := isValidBasepath(swaggerObj.BasePath); !ok {
        whisk.Debug(whisk.DbgError, "Swagger file basePath is invalid.\n", flags.api.configfile, err)
        errMsg := wski18n.T("Swagger file basePath must start with a leading slash (/)")
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }

    api := new(whisk.Api)
    api.Namespace = Client.Config.Namespace
    api.Swagger = swagger

    return api, nil
}

func getAccessToken() (string, error) {
    var token string = "DUMMY TOKEN"
    var err error

    props, err := ReadProps(Properties.PropsFile)
    if err == nil {
        if len(props["APIGW_ACCESS_TOKEN"]) > 0 {
            token = props["APIGW_ACCESS_TOKEN"]
        }
    } else {
        whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
        errStr := wski18n.T("Unable to obtain the API Gateway access token from the properties file: {{.err}}", map[string]interface{}{"err": err})
        err = whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
    }

    return token, err
}

func getUserContextId() (string, error) {
    var guid string
    var err error

    props, err := ReadProps(Properties.PropsFile)
    if err == nil {
        if len(props["AUTH"]) > 0 {
            guid = strings.Split(props["AUTH"], ":")[0]
        } else {
            whisk.Debug(whisk.DbgError, "AUTH property not set in properties file: '%s'\n", Properties.PropsFile)
            errStr := wski18n.T("Authorization key is not configured (--auth is required)")
            err = whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        }
    } else {
        whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
        errStr := wski18n.T("Unable to obtain the auth key from the properties file: {{.err}}", map[string]interface{}{"err": err})
        err = whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
    }

    return guid, err
}

///////////
// Flags //
///////////

func init() {

    apiCreateCmd.Flags().StringVarP(&flags.api.apiname, "apiname", "n", "", wski18n.T("Friendly name of the API; ignored when CFG_FILE is specified (default BASE_PATH)"))
    apiCreateCmd.Flags().StringVarP(&flags.api.configfile, "config-file", "c", "", wski18n.T("`CFG_FILE` containing API configuration in swagger JSON format"))
    apiCreateCmd.Flags().StringVar(&flags.api.resptype, "response-type", "json", wski18n.T("Set the web action response `TYPE`. Possible values are html, http, json, text, svg"))
    apiGetCmd.Flags().BoolVarP(&flags.common.detail, "full", "f", false, wski18n.T("display full API configuration details"))
    apiGetCmd.Flags().StringVarP(&flags.common.format, "format", "", formatOptionJson, wski18n.T("Specify the API output `TYPE`, either json or yaml"))
    apiListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of actions from the result"))
    apiListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of actions from the collection"))
    apiListCmd.Flags().BoolVarP(&flags.common.nameSort, "name-sort", "n", false, wski18n.T("sorts a list alphabetically by order of [BASE_PATH | API_NAME], API_PATH, then API_VERB; only applicable within the limit/skip returned entity block"))
    apiListCmd.Flags().BoolVarP(&flags.common.full, "full", "f", false, wski18n.T("display full description of each API"))
    apiCmd.AddCommand(
        apiCreateCmd,
        apiGetCmd,
        apiDeleteCmd,
        apiListCmd,
    )
}
