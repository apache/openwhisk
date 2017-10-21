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

package whisk

import (
    "net/http"
    "errors"
    "../wski18n"
    "strings"
    "fmt"
)

type ApiService struct {
    client *Client
}

// wsk api create : Request, Response
type ApiCreateRequest struct {
    ApiDoc          *Api      `json:"apidoc,omitempty"`
}
type ApiCreateRequestOptions ApiOptions
type ApiCreateResponse RetApi

// wsk api list : Request, Response
type ApiListRequest struct {
}
type ApiListRequestOptions struct {
    ApiOptions
    Limit           int       `url:"limit"`
    Skip            int       `url:"skip"`
    Docs            bool      `url:"docs,omitempty"`
}
type ApiListResponse RetApiArray

// wsk api get : Request, Response
type ApiGetRequest struct {
    Api
}
type ApiGetRequestOptions ApiOptions
type ApiGetResponse RetApiArray

// wsk api delete : Request, Response
type ApiDeleteRequest struct {
    Api
}
type ApiDeleteRequestOptions ApiOptions
type ApiDeleteResponse struct {}

type Api struct {
    Namespace       string    `json:"namespace,omitempty"`
    ApiName         string    `json:"apiName,omitempty"`
    GatewayBasePath string    `json:"gatewayBasePath,omitempty"`
    GatewayRelPath  string    `json:"gatewayPath,omitempty"`
    GatewayMethod   string    `json:"gatewayMethod,omitempty"`
    Id              string    `json:"id,omitempty"`
    GatewayFullPath string    `json:"gatewayFullPath,omitempty"`
    Swagger         string    `json:"swagger,omitempty"`
    Action          *ApiAction `json:"action,omitempty"`
}

type ApiAction struct {
    Name            string    `json:"name,omitempty"`
    Namespace       string    `json:"namespace,omitempty"`
    BackendMethod   string    `json:"backendMethod,omitempty"`
    BackendUrl      string    `json:"backendUrl,omitempty"`
    Auth            string    `json:"authkey,omitempty"`
}

type ApiOptions struct {
    ActionName      string    `url:"action,omitempty"`
    ApiBasePath     string    `url:"basepath,omitempty"`
    ApiRelPath      string    `url:"relpath,omitempty"`
    ApiVerb         string    `url:"operation,omitempty"`
    ApiName         string    `url:"apiname,omitempty"`
    SpaceGuid       string    `url:"spaceguid,omitempty"`
    AccessToken     string    `url:"accesstoken,omitempty"`
    ResponseType    string    `url:"responsetype,omitempty"`
}

type ApiUserAuth struct {
    SpaceGuid       string    `json:"spaceguid,omitempty"`
    AccessToken     string    `json:"accesstoken,omitempty"`
}

type RetApiArray struct {
    Apis            []ApiItem `json:"apis,omitempty"`
}

type ApiItem struct {
    ApiId           string    `json:"id,omitempty"`
    QueryKey        string    `json:"key,omitempty"`
    ApiValue        *RetApi   `json:"value,omitempty"`
}

type RetApi struct {
    Namespace       string    `json:"namespace"`
    BaseUrl         string    `json:"gwApiUrl"`
    Activated       bool      `json:"gwApiActivated"`
    TenantId        string    `json:"tenantId"`
    Swagger         *ApiSwagger `json:"apidoc,omitempty"`
}

type ApiSwagger struct {
    SwaggerName     string    `json:"swagger,omitempty"`
    BasePath        string    `json:"basePath,omitempty"`
    Info            *ApiSwaggerInfo `json:"info,omitempty"`
    Paths           map[string]map[string]*ApiSwaggerOperation `json:"paths,omitempty"`  // Paths["/a/path"]["get"] -> a generic object
    SecurityDef     interface{} `json:"securityDefinitions,omitempty"`
    Security        interface{} `json:"security,omitempty"`
    XConfig         interface{} `json:"x-ibm-configuration,omitempty"`
    XRateLimit      interface{} `json:"x-ibm-rate-limit,omitempty"`
}

type ApiSwaggerInfo struct {
    Title           string    `json:"title,omitempty"`
    Version         string    `json:"version,omitempty"`
}

type ApiSwaggerOperation struct {
    OperationId     string    `json:"operationId"`
    Responses       interface{} `json:"responses"`
    XOpenWhisk      *ApiSwaggerOpXOpenWhisk `json:"x-openwhisk,omitempty"`
}

type ApiSwaggerOpXOpenWhisk struct {
    ActionName      string    `json:"action"`
    Namespace       string    `json:"namespace"`
    Package         string    `json:"package"`
    ApiUrl          string    `json:"url"`
}

// Used for printing individual APIs in non-truncated form
type ApiFilteredList struct {
    ActionName      string
    ApiName         string
    BasePath        string
    RelPath         string
    Verb            string
    Url             string
}

// Used for printing individual APIs in truncated form
type ApiFilteredRow struct {
    ActionName      string
    ApiName         string
    BasePath        string
    RelPath         string
    Verb            string
    Url             string
    FmtString       string
}

var ApiVerbs map[string]bool = map[string]bool {
    "GET": true,
    "PUT": true,
    "POST": true,
    "DELETE": true,
    "PATCH": true,
    "HEAD": true,
    "OPTIONS": true,
}

const (
    Overwrite = true
    DoNotOverwrite = false
)

/////////////////
// Api Methods //
/////////////////

// Compare(sortable) compares api to sortable for the purpose of sorting.
// REQUIRED: sortable must also be of type ApiFilteredList.
// ***Method of type Sortable***
func(api ApiFilteredList) Compare(sortable Sortable) (bool) {
    // Sorts alphabetically by [BASE_PATH | API_NAME] -> REL_PATH -> API_VERB
    apiToCompare := sortable.(ApiFilteredList)
    var apiString string
    var compareString string

    apiString = strings.ToLower(fmt.Sprintf("%s%s%s",api.BasePath, api.RelPath,
        api.Verb))
    compareString = strings.ToLower(fmt.Sprintf("%s%s%s", apiToCompare.BasePath,
        apiToCompare.RelPath, apiToCompare.Verb))

    return apiString < compareString
}

// ToHeaderString() returns the header for a list of apis
func(api ApiFilteredList) ToHeaderString() string {
    return ""
}

// ToSummaryRowString() returns a compound string of required parameters for printing
//   from CLI command `wsk api list` or `wsk api-experimental list`.
// ***Method of type Sortable***
func(api ApiFilteredList) ToSummaryRowString() string {
    return fmt.Sprintf("%s %s %s %s %s %s",
        fmt.Sprintf("%s: %s\n", wski18n.T("Action"), api.ActionName),
        fmt.Sprintf("  %s: %s\n", wski18n.T("API Name"), api.ApiName),
        fmt.Sprintf("  %s: %s\n", wski18n.T("Base path"), api.BasePath),
        fmt.Sprintf("  %s: %s\n", wski18n.T("Path"), api.RelPath),
        fmt.Sprintf("  %s: %s\n", wski18n.T("Verb"), api.Verb),
        fmt.Sprintf("  %s: %s\n", wski18n.T("URL"), api.Url))
}

// Compare(sortable) compares api to sortable for the purpose of sorting.
// REQUIRED: sortable must also be of type ApiFilteredRow.
// ***Method of type Sortable***
func(api ApiFilteredRow) Compare(sortable Sortable) (bool) {
    // Sorts alphabetically by [BASE_PATH | API_NAME] -> REL_PATH -> API_VERB
    var apiString string
    var compareString string
    apiToCompare := sortable.(ApiFilteredRow)

    apiString = strings.ToLower(fmt.Sprintf("%s%s%s",api.BasePath, api.RelPath,
        api.Verb))
    compareString = strings.ToLower(fmt.Sprintf("%s%s%s", apiToCompare.BasePath,
        apiToCompare.RelPath, apiToCompare.Verb))

    return apiString < compareString
}

// ToHeaderString() returns the header for a list of apis
func(api ApiFilteredRow) ToHeaderString() string {
    return fmt.Sprintf("%s", fmt.Sprintf(api.FmtString, "Action", "Verb", "API Name", "URL"))
}

// ToSummaryRowString() returns a compound string of required parameters for printing
//   from CLI command `wsk api list -f` or `wsk api-experimental list -f`.
// ***Method of type Sortable***
func(api ApiFilteredRow) ToSummaryRowString() string {
  return fmt.Sprintf(api.FmtString, api.ActionName, api.Verb, api.ApiName, api.Url)
}

func (s *ApiService) List(apiListOptions *ApiListRequestOptions) (*ApiListResponse, *http.Response, error) {
    route := "web/whisk.system/apimgmt/getApi.http"

    routeUrl, err := addRouteOptions(route, apiListOptions)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, apiListOptions, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": apiListOptions})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgInfo, "Api GET/list route with api options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", routeUrl, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": routeUrl, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    apiArray := new(ApiListResponse)
    resp, err := s.client.Do(req, &apiArray, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    err = validateApiListResponse(apiArray)
    if err != nil {
        Debug(DbgError, "Not a valid ApiListReponse object\n")
        return nil, resp, err
    }

    return apiArray, resp, err
}

func (s *ApiService) Insert(api *ApiCreateRequest, options *ApiCreateRequestOptions, overwrite bool) (*ApiCreateResponse, *http.Response, error) {
    route := "web/whisk.system/apimgmt/createApi.http"
    Debug(DbgInfo, "Api PUT route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api create route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("POST", routeUrl, api, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(POST, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    retApi := new(ApiCreateResponse)
    resp, err := s.client.Do(req, &retApi, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    err = validateApiSwaggerResponse(retApi.Swagger)
    if err != nil {
        Debug(DbgError, "Not a valid API creation response\n")
        return nil, resp, err
    }

    return retApi, resp, nil
}

func (s *ApiService) Get(api *ApiGetRequest, options *ApiGetRequestOptions) (*ApiGetResponse, *http.Response, error) {
    route := "web/whisk.system/apimgmt/getApi.http"
    Debug(DbgInfo, "Api GET route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api get route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    retApi := new(ApiGetResponse)
    resp, err := s.client.Do(req, &retApi, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return retApi, resp, nil
}

func (s *ApiService) Delete(api *ApiDeleteRequest, options *ApiDeleteRequestOptions) (*http.Response, error) {
    route := "web/whisk.system/apimgmt/deleteApi.http"
    Debug(DbgInfo, "Api DELETE route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }
    Debug(DbgError, "Api DELETE route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("DELETE", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(DELETE, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXIT_CODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }

    retApi := new(ApiDeleteResponse)
    resp, err := s.client.Do(req, &retApi, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return resp, err
    }

    return nil, nil
}


func validateApiListResponse(apiList *ApiListResponse) error {
    for i := 0; i < len(apiList.Apis); i++ {
        if apiList.Apis[i].ApiValue == nil {
            Debug(DbgError, "validateApiResponse: No value stanza in api %v\n", apiList.Apis[i])
            errMsg := wski18n.T("Internal error. Missing value stanza in API configuration response")
            whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return whiskErr
        }
        err := validateApiSwaggerResponse(apiList.Apis[i].ApiValue.Swagger)
        if (err != nil) {
            Debug(DbgError, "validateApiListResponse: Invalid Api: %v\n", apiList.Apis[i])
            return err
        }
    }
    return nil
}

func validateApiSwaggerResponse(swagger *ApiSwagger) error {
    if swagger == nil {
        Debug(DbgError, "validateApiSwaggerResponse: No apidoc stanza in api\n")
        errMsg := wski18n.T("Internal error. Missing apidoc stanza in API configuration")
        whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    for path, _ := range swagger.Paths {
        err := validateApiPath(swagger.Paths[path])
        if err != nil {
            Debug(DbgError, "validateApiResponse: Invalid Api Path object: %v\n", swagger.Paths[path])
            return err
        }
    }

    return nil
}

func validateApiPath(path map[string]*ApiSwaggerOperation) error {
    for op, opv := range path {
        err := validateApiOperation(op, opv)
        if err != nil {
            Debug(DbgError, "validateApiPath: Invalid Api operation object: %v\n", opv)
            return err
        }
    }
    return nil
}

func validateApiOperation(opName string, op *ApiSwaggerOperation) error {
    if (op.XOpenWhisk != nil && len(op.OperationId) == 0) {
        Debug(DbgError, "validateApiOperation: No operationId field in operation %v\n", op)
        errMsg := wski18n.T("Missing operationId field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }

    if (op.XOpenWhisk != nil && len(op.XOpenWhisk.Namespace) == 0) {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.namespace stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.namespace field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }

    // Note: The op.XOpenWhisk.Package field can have a value of "", so don't enforce a value

    if (op.XOpenWhisk != nil && len(op.XOpenWhisk.ActionName) == 0) {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.action stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.action field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    if (op.XOpenWhisk != nil && len(op.XOpenWhisk.ApiUrl) == 0) {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.url stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.url field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXIT_CODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    return nil
}
