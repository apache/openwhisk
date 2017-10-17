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
    "net/url"
    "errors"
    "../wski18n"
    "fmt"
)

type Info struct {
    Whisk   string `json:"whisk,omitempty"`
    Version string `json:"version,omitempty"`
    Build   string `json:"build,omitempty"`
    BuildNo string `json:"buildno,omitempty"`
}

type InfoService struct {
    client *Client
}

func (s *InfoService) Get() (*Info, *http.Response, error) {
    // make a request to c.BaseURL / v1
    err := s.client.LoadX509KeyPair()
    if err != nil {
        return nil, nil, err
    }
    urlStr := fmt.Sprintf("%s/%s", s.client.BaseURL.String(), s.client.Config.Version)
    u, err := url.Parse(urlStr)
    if err != nil {
        Debug(DbgError, "url.Parse(%s) error: %s\n", urlStr, err)
        errStr := wski18n.T("Unable to URL parse '{{.version}}': {{.err}}",
            map[string]interface{}{"version": urlStr, "err": err})
        werr := MakeWskError(errors.New(errStr), EXIT_CODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    req, err := http.NewRequest("GET", u.String(), nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s) error: %s\n", u.String(), err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.url}}': {{.err}}",
            map[string]interface{}{"url": u.String(), "err": err})
        werr := MakeWskError(errors.New(errStr), EXIT_CODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    Debug(DbgInfo, "Sending HTTP URL '%s'; req %#v\n", req.URL.String(), req)
    info := new(Info)
    resp, err := s.client.Do(req, &info, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, nil, err
    }

    return info, resp, nil
}
