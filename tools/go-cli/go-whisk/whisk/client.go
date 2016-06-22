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
)

const (
    defaultBaseURL = "openwhisk.ng.bluemix.net"
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
        Debug(DbgInfo, "Disabling certificate checking.")

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
            Debug(DbgError, "url.Parse(%s) error: %s", defaultBaseURL, err)
            errStr := fmt.Sprintf("Unable to create request URL '%s': %s", defaultBaseURL, err)
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

    return c, nil
}

///////////////////////////////
// Request/Utility Functions //
///////////////////////////////

func (c *Client) NewRequest(method, urlStr string, body interface{}) (*http.Request, error) {
    urlStr = fmt.Sprintf("%s/namespaces/%s/%s", c.Config.Version, c.Config.Namespace, urlStr)

    rel, err := url.Parse(urlStr)
    if err != nil {
        Debug(DbgError, "url.Parse(%s) error: %s\n", urlStr, err)
        errStr := fmt.Sprintf("Invalid request URL '%s': %s", urlStr, err)
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
            errStr := fmt.Sprintf("Error encoding request body: %s", err)
            werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return nil, werr
        }
    }
    req, err := http.NewRequest(method, u.String(), buf)
    if err != nil {
        Debug(DbgError, "http.NewRequest(%v, %s, buf) error: %s\n", method, u.String(), err)
        errStr := fmt.Sprintf("Error initializing request: %s", err)
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }
    if req.Body != nil {
        req.Header.Add("Content-Type", "application/json")
    }

    c.addAuthHeader(req)

    return req, nil
}

func (c *Client) addAuthHeader(req *http.Request) {
    if c.Config.AuthToken != "" {
        encodedAuthToken := base64.StdEncoding.EncodeToString([]byte(c.Config.AuthToken))
        req.Header.Add("Authorization", fmt.Sprintf("Basic %s", encodedAuthToken))
    }
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

    // Read the response body
    data, err := ioutil.ReadAll(resp.Body)
    if err != nil {
        Debug(DbgError, "ioutil.ReadAll(resp.Body) error: %s\n", err)
        werr := MakeWskError(err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }
    Verbose("Response body size is %d bytes\n", len(data))
    Verbose("Response body received:\n%s\n", string(data))

    // With the HTTP response status code and the HTTP body contents,
    // the possible response scenarios are:
    //
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
        werr := MakeWskError(errors.New("Command failed due to an HTTP failure"), resp.StatusCode-256, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }

    // Handle 5. HTTP Failure + Body matching error format expectation
    // Handle 6. HTTP Failure + Body NOT matching error format expectation
    if !IsHttpRespSuccess(resp) && data != nil {
        Debug(DbgInfo, "HTTP failure %d + body\n", resp.StatusCode)
        errorResponse := &ErrorResponse{Response: resp}
        err = json.Unmarshal(data, errorResponse)

        // If the body matches the error response format, return an error containing
        // the response error information (#5)
        if err == nil {
            Debug(DbgInfo, "HTTP failure %d; server error %s\n", resp.StatusCode, errorResponse)
            werr := MakeWskError(errorResponse, resp.StatusCode - 256, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return resp, werr
        } else {
            // Otherwise, the body contents are unknown (#6)
            Debug(DbgError, "HTTP response with unexpected body failed due to contents parsing error: '%v'\n", err)
            var errStr = fmt.Sprintf("Request failed (status code = %d). Error details: %s", resp.StatusCode, data)
            werr := MakeWskError(errors.New(errStr), resp.StatusCode - 256, DISPLAY_MSG, NO_DISPLAY_USAGE)
            return resp, werr
        }
    }

    // Handle 2. HTTP Success + No body expected
    if IsHttpRespSuccess(resp) && v == nil {
        Debug(DbgInfo, "No interface provided; no HTTP response body expected")
        return resp, nil
    }

    // Handle 1. HTTP Success + Valid body matching request expectations
    // Handle 3. HTTP Success + Body does NOT match request expectations
    if IsHttpRespSuccess(resp) && v != nil {
        Debug(DbgInfo, "Parsing HTTP response into struct type: %s\n", reflect.TypeOf(v) )
        err = json.Unmarshal(data, v)

        // If the decode was successful, return the response without error (#1)
        if err == nil {
            Debug(DbgInfo, "Successful parse of HTTP response into struct type: %s\n", reflect.TypeOf(v))
            return resp, nil
        } else {
            // The decode did not work, so the server response was unexpected (#3)
            Debug(DbgWarn, "Unsuccessful parse of HTTP response into struct type: %s; parse error '%v'\n", reflect.TypeOf(v), err)
            Debug(DbgWarn, "Request was successful, so ignoring the following unexpected response body that could not be parsed: %s\n", data)
            return resp, nil
        }
    }

    // We should never get here, but just in case return failure
    // to keep the compiler happy
    werr := MakeWskError(errors.New("Command failed due to an internal failure"), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
    return resp, werr
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
    Response *http.Response         // HTTP response that caused this error
    ErrMsg   string `json:"error"`  // error message string
    Code     int64  `json:"code"`   // validation error code
}

func (r ErrorResponse) Error() string {
    return fmt.Sprintf("%v (code %d)", r.ErrMsg, r.Code)
}

////////////////////////////
// Basic Client Functions //
////////////////////////////

func IsHttpRespSuccess(r *http.Response) bool {
    return r.StatusCode >= 200 && r.StatusCode <= 299
}
