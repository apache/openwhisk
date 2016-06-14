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
    "fmt"
    "errors"
    "net/http"
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

    urlStr := fmt.Sprintf("https://%s/%s", s.client.Config.BaseURL.Host, relFileUrl)

    req, err := http.NewRequest("GET", urlStr, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: %s\n", urlStr, err)
        errStr := fmt.Sprintf("Unable to create HTTP request for GET '%s'; error: %s", urlStr, err)
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    s.client.addAuthHeader(req)

    // Directly use the HTTP client, not the Whisk CLI client, so that the response body is left alone
    resp, err := s.client.client.Do(req)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errStr := fmt.Sprintf("Request failure: %s", err)
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }

    return resp, nil
}
