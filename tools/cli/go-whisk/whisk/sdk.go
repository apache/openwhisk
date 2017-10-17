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
    "fmt"
    "errors"
    "net/http"
    "../wski18n"
)

type SdkService struct {
    client *Client
}

// Structure for SDK request responses
type Sdk struct {
    // TODO :: Add SDK fields
}

type SdkRequest struct {
    // TODO :: Add SDK
}

// Install artifact {component = docker || swift || iOS}
func (s *SdkService) Install(relFileUrl string) (*http.Response, error) {
    err := s.client.LoadX509KeyPair()
    if err != nil {
        return nil, err
    }
    baseURL := s.client.Config.BaseURL
    // Remove everything but the scheme, host, and port
    baseURL.Path, baseURL.RawQuery, baseURL.Fragment = "", "", ""

    urlStr := fmt.Sprintf("%s/%s", baseURL, relFileUrl)

    req, err := http.NewRequest("GET", urlStr, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: %s\n", urlStr, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.url}}': {{.err}}",
            map[string]interface{}{"url": urlStr, "err": err})
        werr := MakeWskError(errors.New(errStr), EXIT_CODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    if IsVerbose() {
        fmt.Println("REQUEST:")
        fmt.Printf("[%s]\t%s\n", req.Method, req.URL)
        if len(req.Header) > 0 {
            fmt.Println("Req Headers")
            PrintJSON(req.Header)
        }
        if req.Body != nil {
            fmt.Println("Req Body")
            fmt.Println(req.Body)
        }
    }

    // Directly use the HTTP client, not the Whisk CLI client, so that the response body is left alone
    resp, err := s.client.client.Do(req)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return resp, err
    }

    return resp, nil
}
