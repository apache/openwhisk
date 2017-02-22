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
    "bytes"
    "encoding/base64"
    "encoding/json"
    "fmt"
    "io"
    "io/ioutil"
    "net/http"
    "net/url"
    "crypto/tls"
    "errors"
    "reflect"
    "../wski18n"
    "strings"
)

const (
    defaultBaseURL = "openwhisk.ng.bluemix.net"
    AuthRequired = true
    AuthOptional = false
    IncludeNamespaceInUrl = true
    DoNotIncludeNamespaceInUrl = false
)

type Client struct {
    client *http.Client
    *Config
    Transport *http.Transport

    Sdks        *SdkService
    Triggers    *TriggerService
    Actions     *ActionService
    Rules       *RuleService
    Activations *ActivationService
    Packages    *PackageService
    Namespaces  *NamespaceService
    Info        *InfoService
    Apis        *ApiService
}

type Config struct {
    Namespace 	string // NOTE :: Default is "_"
    AuthToken 	string
    Host		string
    BaseURL   	*url.URL // NOTE :: Default is "openwhisk.ng.bluemix.net"
    Version   	string
    Verbose   	bool
    Debug       bool     // For detailed tracing
    Insecure    bool
}

func NewClient(httpClient *http.Client, config *Config) (*Client, error) {

    // Disable certificate checking in the dev environment if in insecure mode
    if config.Insecure {
        Debug(DbgInfo, "Disabling certificate checking.\n")

        tlsConfig := &tls.Config{
            InsecureSkipVerify: true,
        }

        http.DefaultClient.Transport = &http.Transport{
            TLSClientConfig: tlsConfig,
        }
    }

    if httpClient == nil {
        httpClient = http.DefaultClient
    }

    var err error
    if config.BaseURL == nil {
        config.BaseURL, err = url.Parse(defaultBaseURL)
        if err != nil {
            Debug(DbgError, "url.Parse(%s) error: %s\n", defaultBaseURL, err)
            errStr := wski18n.T("Unable to create request URL '{{.url}}': {{.err}}",
                map[string]interface{}{"url": defaultBaseURL, "err": err})
            werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return nil, werr
        }
    }

    if len(config.Namespace) == 0 {
        config.Namespace = "_"
    }

    if len(config.Version) == 0 {
        config.Version = "v1"
    }

    c := &Client{
        client: httpClient,
        Config: config,
    }

    c.Sdks = &SdkService{client: c}
    c.Triggers = &TriggerService{client: c}
    c.Actions = &ActionService{client: c}
    c.Rules = &RuleService{client: c}
    c.Activations = &ActivationService{client: c}
    c.Packages = &PackageService{client: c}
    c.Namespaces = &NamespaceService{client: c}
    c.Info = &InfoService{client: c}
    c.Apis = &ApiService{client: c}

    return c, nil
}

///////////////////////////////
// Request/Utility Functions //
///////////////////////////////

func (c *Client) NewRequest(method, urlStr string, body interface{}, includeNamespaceInUrl bool) (*http.Request, error) {
    if (includeNamespaceInUrl) {
        if c.Config.Namespace != "" {
            urlStr = fmt.Sprintf("%s/namespaces/%s/%s", c.Config.Version, c.Config.Namespace, urlStr)
        } else {
            urlStr = fmt.Sprintf("%s/namespaces", c.Config.Version)
        }
    } else {
        urlStr = fmt.Sprintf("%s/%s", c.Config.Version, urlStr)
    }

    rel, err := url.Parse(urlStr)
    if err != nil {
        Debug(DbgError, "url.Parse(%s) error: %s\n", urlStr, err)
        errStr := wski18n.T("Invalid request URL '{{.url}}': {{.err}}",
            map[string]interface{}{"url": urlStr, "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    u := c.BaseURL.ResolveReference(rel)

    var buf io.ReadWriter
    if body != nil {
        buf = new(bytes.Buffer)
        err := json.NewEncoder(buf).Encode(body)
        if err != nil {
            Debug(DbgError, "json.Encode(%#v) error: %s\n", body, err)
            errStr := wski18n.T("Error encoding request body: {{.err}}", map[string]interface{}{"err": err})
            werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return nil, werr
        }
    }

    req, err := http.NewRequest(method, u.String(), buf)
    if err != nil {
        Debug(DbgError, "http.NewRequest(%v, %s, buf) error: %s\n", method, u.String(), err)
        errStr := wski18n.T("Error initializing request: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }
    if req.Body != nil {
        req.Header.Add("Content-Type", "application/json")
    }

    err = c.addAuthHeader(req, AuthRequired)
    if err != nil {
        Debug(DbgError, "addAuthHeader() error: %s\n", err)
        errStr := wski18n.T("Unable to add the HTTP authentication header: {{.err}}",
            map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    return req, nil
}

func (c *Client) addAuthHeader(req *http.Request, authRequired bool) error {
    if c.Config.AuthToken != "" {
        encodedAuthToken := base64.StdEncoding.EncodeToString([]byte(c.Config.AuthToken))
        req.Header.Add("Authorization", fmt.Sprintf("Basic %s", encodedAuthToken))
    } else {
        if authRequired {
            Debug(DbgError, "The required authorization key is not configured - neither set as a property nor set via the --auth CLI argument\n")
            errStr := wski18n.T("Authorization key is not configured (--auth is required)")
            werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_USAGE, DISPLAY_MSG, DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}

// Do sends an API request and returns the API response.  The API response is
// JSON decoded and stored in the value pointed to by v, or returned as an
// error if an API error has occurred.  If v implements the io.Writer
// interface, the raw response body will be written to v, without attempting to
// first decode it.
func (c *Client) Do(req *http.Request, v interface{}) (*http.Response, error) {
    if IsVerbose() {
        fmt.Println("REQUEST:")
        fmt.Printf("[%s]\t%s\n", req.Method, req.URL)
        if len(req.Header) > 0 {
            fmt.Println("Req Headers")
            printJSON(req.Header)
        }
        if req.Body != nil {
            fmt.Println("Req Body")
            fmt.Println(req.Body)
            Debug(DbgInfo, "Req Body (ASCII quoted string):\n%+q\n", req.Body)
        }
    }

    // Issue the request to the Whisk server endpoint
    resp, err := c.client.Do(req)
    if err != nil {
        Debug(DbgError, "HTTP Do() [req %s] error: %s\n", req.URL.String(), err)
        werr := MakeWskError(err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }
    defer resp.Body.Close()
    Verbose("RESPONSE:")
    Verbose("Got response with code %d\n", resp.StatusCode)
    if (IsVerbose() && len(resp.Header) > 0) {
        fmt.Println("Resp Headers")
        printJSON(resp.Header)
    }

    // Read the response body
    data, err := ioutil.ReadAll(resp.Body)
    if err != nil {
        Debug(DbgError, "ioutil.ReadAll(resp.Body) error: %s\n", err)
        werr := MakeWskError(err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }
    Verbose("Response body size is %d bytes\n", len(data))
    Verbose("Response body received:\n%s\n", string(data))
    Debug(DbgInfo, "Response body received (ASCII quoted string):\n%+q\n", string(data))

    // With the HTTP response status code and the HTTP body contents,
    // the possible response scenarios are:
    //
    // 0. HTTP Success + Body indicating a whisk failure result
    // 1. HTTP Success + Valid body matching request expectations
    // 2. HTTP Success + No body expected
    // 3. HTTP Success + Body does NOT match request expectations
    // 4. HTTP Failure + No body
    // 5. HTTP Failure + Body matching error format expectation
    // 6. HTTP Failure + Body NOT matching error format expectation

    // Handle 4. HTTP Failure + No body
    // If this happens, just return no data and an error
    if !IsHttpRespSuccess(resp) && data == nil {
        Debug(DbgError, "HTTP failure %d + no body\n", resp.StatusCode)
        werr := MakeWskError(errors.New(wski18n.T("Command failed due to an HTTP failure")), resp.StatusCode-256,
            DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }

    // Handle 5. HTTP Failure + Body matching error format expectation, or body matching a whisk.error() response
    // Handle 6. HTTP Failure + Body NOT matching error format expectation
    if !IsHttpRespSuccess(resp) && data != nil {
        return parseErrorResponse(resp, data, v)
    }

    // Handle 0. HTTP Success + Body indicating a whisk failure result
    //   NOTE: Need to ignore activation records send in response to 'wsk get activation NNN` as
    //         these will report the same original error giving the appearance that the command failed.
    if (IsHttpRespSuccess(resp) &&                                      // HTTP Status == 200
        data!=nil &&                                                    // HTTP response body exists
        v != nil &&
        !strings.Contains(reflect.TypeOf(v).String(), "Activation") &&  // Request is not `wsk activation get`
        !IsResponseResultSuccess(data)) {                               // HTTP response body has Whisk error result
        Debug(DbgInfo, "Got successful HTTP; but activation response reports an error\n")
        return parseErrorResponse(resp, data, v)
    }

    // Handle 2. HTTP Success + No body expected
    if IsHttpRespSuccess(resp) && v == nil {
        Debug(DbgInfo, "No interface provided; no HTTP response body expected\n")
        return resp, nil
    }

    // Handle 1. HTTP Success + Valid body matching request expectations
    // Handle 3. HTTP Success + Body does NOT match request expectations
    if IsHttpRespSuccess(resp) && v != nil {
        return parseSuccessResponse(resp, data, v), nil
    }

    // We should never get here, but just in case return failure
    // to keep the compiler happy
    werr := MakeWskError(errors.New(wski18n.T("Command failed due to an internal failure")), EXITCODE_ERR_GENERAL,
        DISPLAY_MSG, NO_DISPLAY_USAGE)
    return resp, werr
}

func parseErrorResponse(resp *http.Response, data []byte, v interface{}) (*http.Response, error) {
    Debug(DbgInfo, "HTTP failure %d + body\n", resp.StatusCode)
    errorResponse := &ErrorResponse{Response: resp}
    err := json.Unmarshal(data, errorResponse)

    // If the body matches the error response format, return an error containing the response error information.
    // If an whisk.error() response was received parse the application error. (#5) Otherwise, the body contents are
    // unknown (#6).
    if err == nil {
        if errorResponse.Code == 0 && len(errorResponse.ErrMsg) == 0 {
            Debug(DbgInfo, "Error code, or error message is null\n")
            return parseWhiskErrorResponse(resp, data, v)
        } else {
            Debug(DbgInfo, "HTTP failure %d; server error %s\n", resp.StatusCode, errorResponse)
            werr := MakeWskError(errorResponse, resp.StatusCode - 256, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return resp, werr
        }
    } else {
        Debug(DbgInfo, "Detected response status `%s` that an application error was returned\n")
        errMsg := wski18n.T("The following application error was received: {{.err}}",
            map[string]interface{}{"err": string(data)})
        whiskErr := MakeWskError(errors.New(errMsg), resp.StatusCode - 256, NO_DISPLAY_MSG, NO_DISPLAY_USAGE,
            NO_MSG_DISPLAYED, APPLICATION_ERR)
        return parseSuccessResponse(resp, data, v), whiskErr
    }
}

func parseWhiskErrorResponse(resp *http.Response, data []byte, v interface{}) (*http.Response, error) {
    whiskErrorResponse := &WhiskErrorResponse{}
    err := json.Unmarshal(data, whiskErrorResponse)

    // Determine if a whisk.error() response was received. Otherwise, the body contents are unknown (#6)
    if err == nil && whiskErrorResponse.Response.Status != nil {
        Debug(DbgInfo, "Detected response status `%s` that a whisk.error(\"%s\") was returned\n",
            *whiskErrorResponse.Response.Status, *whiskErrorResponse.Response.Result)
        errMsg := wski18n.T("The following application error was received: {{.err}}",
            map[string]interface{}{"err": *whiskErrorResponse.Response.Result})
        whiskErr := MakeWskError(errors.New(errMsg), resp.StatusCode - 256, NO_DISPLAY_MSG, NO_DISPLAY_USAGE,
            NO_MSG_DISPLAYED, APPLICATION_ERR)
        return parseSuccessResponse(resp, data, v), whiskErr
    } else {
        Debug(DbgError, "HTTP response with unexpected body failed due to contents parsing error: '%v'\n", err)
        errMsg := wski18n.T("The connection failed, or timed out. (HTTP status code {{.code}})",
            map[string]interface{}{"code": resp.StatusCode})
        whiskErr := MakeWskError(errors.New(errMsg), resp.StatusCode - 256, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, whiskErr
    }
}

func parseSuccessResponse(resp *http.Response, data []byte, v interface{}) (*http.Response) {
    Debug(DbgInfo, "Parsing HTTP response into struct type: %s\n", reflect.TypeOf(v))

    dc := json.NewDecoder(strings.NewReader(string(data)))
    dc.UseNumber()
    err := dc.Decode(v)

    // If the decode was successful, return the response without error (#1). Otherwise, the decode did not work, so the
    // server response was unexpected (#3)
    if err == nil {
        Debug(DbgInfo, "Successful parse of HTTP response into struct type: %s\n", reflect.TypeOf(v))
        return resp
    } else {
        Debug(DbgWarn, "Unsuccessful parse of HTTP response into struct type: %s; parse error '%v'\n", reflect.TypeOf(v), err)
        Debug(DbgWarn, "Request was successful, so ignoring the following unexpected response body that could not be parsed: %s\n", data)
        return resp
    }
}

////////////
// Errors //
////////////

// For containing the server response body when an error message is returned
// Here's an example error response body with HTTP status code == 400
// {
//     "error": "namespace contains invalid characters",
//     "code": 1422870
// }
type ErrorResponse struct {
    Response *http.Response                     // HTTP response that caused this error
    ErrMsg   string             `json:"error"`  // error message string
    Code     int64              `json:"code"`   // validation error code
}

type WhiskErrorResponse struct {
    Response *WhiskResponse     `json:"response"`
}

type WhiskResponse struct {
    Result  *WhiskResult        `json:"result"`
    Success bool                `json:"success"`
    Status  *string             `json:"status"`
}

type WhiskResult struct {
//  Error   *WhiskError         `json:"error"`  // whisk.error(<string>) and whisk.reject({msg:<string>}) result in two different kinds of 'error' JSON objects
}

type WhiskError struct {
    Msg     *string             `json:"msg"`
}

func (r ErrorResponse) Error() string {
    return wski18n.T("{{.msg}} (code {{.code}})",
        map[string]interface{}{"msg": fmt.Sprintf("%v", r.ErrMsg), "code": r.Code})
}

////////////////////////////
// Basic Client Functions //
////////////////////////////

func IsHttpRespSuccess(r *http.Response) bool {
    return r.StatusCode >= 200 && r.StatusCode <= 299
}

func IsResponseResultSuccess(data []byte) bool {
    errResp := new(WhiskErrorResponse)
    err := json.Unmarshal(data, &errResp)
    if (err == nil && errResp.Response != nil) {
        return errResp.Response.Success
    }
    Debug(DbgWarn, "IsResponseResultSuccess: failed to parse response result: %v\n", err)
    return true;
}

//
// Create a HTTP request object using URL stored in url.URL object
// Arguments:
//   method         - HTTP verb (i.e. "GET", "PUT", etc)
//   urlRelResource - *url.URL structure representing the relative resource URL, including query params
//   body           - optional. Object whose contents will be JSON encoded and placed in HTTP request body
//   includeNamespaceInUrl - when true "/namespaces/NAMESPACE" is included in the final URL; otherwise not included.
func (c *Client) NewRequestUrl(method string, urlRelResource *url.URL, body interface{}, includeNamespaceInUrl bool) (*http.Request, error) {
    var urlVerNamespaceStr string
    var verPathEncoded = (&url.URL{Path: c.Config.Version}).String()

    if (includeNamespaceInUrl) {
        if c.Config.Namespace != "" {
            // Encode path parts before inserting them into the URI so that any '?' is correctly encoded
            // as part of the path and not the start of the query params
            verNamespaceEncoded := (&url.URL{Path: c.Config.Namespace}).String()
            urlVerNamespaceStr = fmt.Sprintf("%s/namespaces/%s/", verPathEncoded, verNamespaceEncoded)
        } else {
            urlVerNamespaceStr = fmt.Sprintf("%s/namespaces/", c.Config.Version)
        }
    } else {
        urlVerNamespaceStr = fmt.Sprintf("%s/", verPathEncoded)
    }

    urlVerNamespace, err := url.Parse(urlVerNamespaceStr)
    if err != nil {
        Debug(DbgError, "url.Parse(%s) error: %s\n", urlVerNamespaceStr, err)
        errStr := wski18n.T("Invalid request URL '{{.url}}': {{.err}}",
            map[string]interface{}{"url": urlVerNamespaceStr, "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    // URL construction order is important so that parts of the path are not lost
    // 1. Combine absolute Base URL path with the relative namespace path.  The creates an absolute URL
    // 2. Combine the Base+Namespace absolute URL with the relative resource path (including query params)
    u := c.BaseURL.ResolveReference(urlVerNamespace)
    u = u.ResolveReference(urlRelResource)

    var buf io.ReadWriter
    if body != nil {
        buf = new(bytes.Buffer)
        err := json.NewEncoder(buf).Encode(body)
        if err != nil {
            Debug(DbgError, "json.Encode(%#v) error: %s\n", body, err)
            errStr := wski18n.T("Error encoding request body: {{.err}}",
                map[string]interface{}{"err": err})
            werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return nil, werr
        }
    }
    req, err := http.NewRequest(method, u.String(), buf)
    if err != nil {
        Debug(DbgError, "http.NewRequest(%v, %s, buf) error: %s\n", method, u.String(), err)
        errStr := wski18n.T("Error initializing request: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }
    if req.Body != nil {
        req.Header.Add("Content-Type", "application/json")
    }

    err = c.addAuthHeader(req, AuthRequired)
    if err != nil {
        Debug(DbgError, "addAuthHeader() error: %s\n", err)
        errStr := wski18n.T("Unable to add the HTTP authentication header: {{.err}}",
            map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    return req, nil
}
