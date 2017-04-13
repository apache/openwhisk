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

package whisk

import (
    "net/http"
    "errors"
    "../wski18n"
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
type ApiCreateResponseV2 RetApiV2

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
type ApiListResponseV2 RetApiArrayV2

// wsk api get : Request, Response
type ApiGetRequest struct {
    Api
}
type ApiGetRequestOptions ApiOptions
type ApiGetResponse RetApiArray
type ApiGetResponseV2 RetApiArrayV2

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
    Paths           map[string]map[string]*ApiSwaggerOperationV1 `json:"paths,omitempty"`
    XConfig         map[string]map[string][]map[string]map[string]interface{} `json:"x-ibm-configuration,omitempty"`
}

type RetApiArrayV2 struct {
    Apis            []ApiItemV2 `json:"apis,omitempty"`
}

type ApiItemV2 struct {
    ApiId           string    `json:"id,omitempty"`
    QueryKey        string    `json:"key,omitempty"`
    ApiValue        *RetApiV2 `json:"value,omitempty"`
}

type RetApiV2 struct {
    Namespace       string    `json:"namespace"`
    BaseUrl         string    `json:"gwApiUrl"`
    Activated       bool      `json:"gwApiActivated"`
    TenantId        string    `json:"tenantId"`
    Swagger         *ApiSwaggerV2 `json:"apidoc,omitempty"`
}

type ApiSwaggerV2 struct {
    SwaggerName     string    `json:"swagger,omitempty"`
    BasePath        string    `json:"basePath,omitempty"`
    Info            *ApiSwaggerInfo `json:"info,omitempty"`
    Paths           map[string]map[string]*ApiSwaggerOperationV2 `json:"paths,omitempty"`  // Paths["/a/path"]["get"] -> a generic object
    XConfig         map[string]map[string][]map[string]map[string]interface{} `json:"x-ibm-configuration,omitempty"`
}

type ApiSwaggerInfo struct {
    Title           string    `json:"title,omitempty"`
    Version         string    `json:"version,omitempty"`
}

type ApiSwaggerOperationV1 struct {
    Responses       interface{} `json:"responses"`
    XOpenWhisk      *ApiSwaggerOpXOpenWhiskV1 `json:"x-ibm-op-ext,omitempty"`
}

type ApiSwaggerOperationV2 struct {
    OperationId     string    `json:"operationId"`
    Responses       interface{} `json:"responses"`
    XOpenWhisk      *ApiSwaggerOpXOpenWhiskV2 `json:"x-openwhisk,omitempty"`
}

type ApiSwaggerOpXOpenWhiskV1 struct {
    ActionName      string    `json:"actionName"`
    Namespace       string    `json:"actionNamespace"`
    ActionUrlVerb   string    `json:"backendMethod"`
    ActionUrl       string    `json:"backendUrl"`
    Policies        interface{} `json:"policies"`
}

type ApiSwaggerOpXOpenWhiskV2 struct {
    ActionName      string    `json:"action"`
    Namespace       string    `json:"namespace"`
    Package         string    `json:"package"`
    ApiUrl          string    `json:"url"`
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

////////////////////
// Api Methods //
////////////////////

func (s *ApiService) List(apiListOptions *ApiListRequestOptions) (*ApiListResponse, *http.Response, error) {
    route := "experimental/web/whisk.system/routemgmt/getApi.json"
    Debug(DbgInfo, "Api GET/list route: %s\n", route)

    routeUrl, err := addRouteOptions(route, apiListOptions)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, apiListOptions, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": apiListOptions})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgInfo, "Api GET/list route with api options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired) error: '%s'\n", routeUrl, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": routeUrl, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    apiArray := new(ApiListResponse)
    resp, err := s.client.Do(req, &apiArray, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return apiArray, resp, err
}

func (s *ApiService) Insert(api *ApiCreateRequest, options *ApiCreateRequestOptions, overwrite bool) (*ApiCreateResponse, *http.Response, error) {
    route := "experimental/web/whisk.system/routemgmt/createApi.json"
    Debug(DbgInfo, "Api PUT route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api create route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("POST", routeUrl, api, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(POST, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    retApi := new(ApiCreateResponse)
    resp, err := s.client.Do(req, &retApi, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return retApi, resp, nil
}

func (s *ApiService) Get(api *ApiGetRequest, options *ApiGetRequestOptions) (*ApiGetResponse, *http.Response, error) {
    route := "experimental/web/whisk.system/routemgmt/getApi.json"
    Debug(DbgInfo, "Api GET route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api get route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
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
    route := "experimental/web/whisk.system/routemgmt/deleteApi.json"
    Debug(DbgInfo, "Api DELETE route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }
    Debug(DbgError, "Api DELETE route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("DELETE", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(DELETE, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
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

/////////////
// V2 Cmds //
/////////////
func (s *ApiService) ListV2(apiListOptions *ApiListRequestOptions) (*ApiListResponseV2, *http.Response, error) {
    route := "web/whisk.system/apimgmt/getApi.http"

    routeUrl, err := addRouteOptions(route, apiListOptions)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, apiListOptions, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": apiListOptions})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgInfo, "Api GET/list route with api options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", routeUrl, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": routeUrl, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    apiArray := new(ApiListResponseV2)
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

func (s *ApiService) InsertV2(api *ApiCreateRequest, options *ApiCreateRequestOptions, overwrite bool) (*ApiCreateResponseV2, *http.Response, error) {
    route := "web/whisk.system/apimgmt/createApi.http"
    Debug(DbgInfo, "Api PUT route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api create route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("POST", routeUrl, api, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(POST, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    retApi := new(ApiCreateResponseV2)
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

func (s *ApiService) GetV2(api *ApiGetRequest, options *ApiGetRequestOptions) (*ApiGetResponseV2, *http.Response, error) {
    route := "web/whisk.system/apimgmt/getApi.http"
    Debug(DbgInfo, "Api GET route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Api get route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    retApi := new(ApiGetResponseV2)
    resp, err := s.client.Do(req, &retApi, ExitWithErrorOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return retApi, resp, nil
}

func (s *ApiService) DeleteV2(api *ApiDeleteRequest, options *ApiDeleteRequestOptions) (*http.Response, error) {
    route := "web/whisk.system/apimgmt/deleteApi.http"
    Debug(DbgInfo, "Api DELETE route: %s\n", route)

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }
    Debug(DbgError, "Api DELETE route with options: %s\n", routeUrl)

    req, err := s.client.NewRequestUrl("DELETE", routeUrl, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson, AuthRequired)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(DELETE, %s, nil, DoNotIncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
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


func validateApiListResponse(apiList *ApiListResponseV2) error {
    for i:=0; i<len(apiList.Apis); i++ {
        if apiList.Apis[i].ApiValue == nil {
            Debug(DbgError, "validateApiResponse: No value stanza in api %v\n", apiList.Apis[i])
            errMsg := wski18n.T("Internal error. Missing value stanza in API configuration response")
            whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
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

func validateApiSwaggerResponse(swagger *ApiSwaggerV2) error {
    if swagger == nil {
        Debug(DbgError, "validateApiSwaggerResponse: No apidoc stanza in api\n")
        errMsg := wski18n.T("Internal error. Missing apidoc stanza in API configuration")
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
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

func validateApiPath(path map[string]*ApiSwaggerOperationV2) error {
    for op, opv := range path {
        err := validateApiOperation(op, opv)
        if err != nil {
            Debug(DbgError, "validateApiPath: Invalid Api operation object: %v\n", opv)
            return err
        }
    }
    return nil
}

func validateApiOperation(opName string, op *ApiSwaggerOperationV2) error {
    if len(op.OperationId) == 0 {
        Debug(DbgError, "validateApiResponse: No operationId field in operation %v\n", op)
        errMsg := wski18n.T("Missing operationId field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    if op.XOpenWhisk == nil {
        Debug(DbgError, "validateApiResponse: No x-openwhisk stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk stanza in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    if len(op.XOpenWhisk.Namespace) == 0 {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.namespace stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.namespace field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }

    // Note: The op.XOpenWhisk.Package field can have a value of "", so don't enforce a value

    if len(op.XOpenWhisk.ActionName) == 0 {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.action stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.action field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    if len(op.XOpenWhisk.ApiUrl) == 0 {
        Debug(DbgError, "validateApiOperation: no x-openwhisk.url stanza in operation %v\n", op)
        errMsg := wski18n.T("Missing x-openwhisk.url field in API configuration for operation {{.op}}",
            map[string]interface{}{"op": opName})
        whiskErr := MakeWskError(errors.New(errMsg), EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return whiskErr
    }
    return nil
}
