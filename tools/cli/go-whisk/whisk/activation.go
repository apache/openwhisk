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
    "net/http"
    "errors"
    "encoding/json"
    "net/url"
    "../wski18n"
)

type ActivationService struct {
    client *Client
}

type Activation struct {
    Namespace string `json:"namespace,omitempty"`
    Name      string `json:"name,omitempty"`
    Version   string `json:"version,omitempty"`
    Publish   bool   `json:"publish,omitempty"`

    Subject      string `json:"subject,omitempty"`
    ActivationID string `json:"activationId,omitempty"`
    Cause        string `json:"cause,omitempty"`
    Start        int64  `json:"start,omitempty"`        // When action started (in milliseconds since January 1, 1970 UTC)
    End          int64  `json:"end"`                    // Since a 0 is a valid value from server, don't omit
    Response     `json:"response,omitempty"`
    Logs         []string `json:"logs,omitempty"`
    Annotations *json.RawMessage `json:"annotations,omitempty"`
}

type Response struct {
    Status     string `json:"status,omitempty"`
    StatusCode int    `json:"statusCode,omitempty"`
    Success    bool   `json:"success"`
    Result     `json:"result,omitempty"`
}

type Result map[string]interface{}

type ActivationListOptions struct {
    Name  string `url:"name,omitempty"`
    Limit int    `url:"limit"`
    Skip  int    `url:"skip"`
    Since int64  `url:"since,omitempty"`
    Upto  int64  `url:"upto,omitempty"`
    Docs  bool   `url:"docs,omitempty"`
}

//MWD - This structure may no longer be needed as the log format is now a string and not JSON
type Log struct {
    Log    string `json:"log,omitempty"`
    Stream string `json:"stream,omitempty"`
    Time   string `json:"time,omitempty"`
}

func (s *ActivationService) List(options *ActivationListOptions) ([]Activation, *http.Response, error) {
    // TODO :: for some reason /activations only works with "_" as namespace
    s.client.Namespace = "_"
    route := "activations"
    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errStr := wski18n.T("Unable to append options '{{.options}}' to URL route '{{.route}}': {{.err}}",
            map[string]interface{}{"options": fmt.Sprintf("%#v", options), "route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s) error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    Debug(DbgInfo, "Sending HTTP request - URL '%s'; req %#v\n", req.URL.String(), req)

    var activations []Activation
    resp, err := s.client.Do(req, &activations)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return activations, resp, nil

}

func (s *ActivationService) Get(activationID string) (*Activation, *http.Response, error) {
    // TODO :: for some reason /activations/:id only works with "_" as namespace
    s.client.Namespace = "_"

    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    activationID = (&url.URL{Path: activationID}).String()
    route := fmt.Sprintf("activations/%s", activationID)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s) error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    Debug(DbgInfo, "Sending HTTP request - URL '%s'; req %#v\n", req.URL.String(), req)

    a := new(Activation)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return a, resp, nil
}

func (s *ActivationService) Logs(activationID string) (*Activation, *http.Response, error) {
    // TODO :: for some reason /activations/:id/logs only works with "_" as namespace
    s.client.Namespace = "_"
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    activationID = (&url.URL{Path: activationID}).String()
    route := fmt.Sprintf("activations/%s/logs", activationID)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s) error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    Debug(DbgInfo, "Sending HTTP request - URL '%s'; req %#v\n", req.URL.String(), req)

    activation := new(Activation)
    resp, err := s.client.Do(req, &activation)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return activation, resp, nil
}

func (s *ActivationService) Result(activationID string) (*Response, *http.Response, error) {
    // TODO :: for some reason /activations only works with "_" as namespace
    s.client.Namespace = "_"
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    activationID = (&url.URL{Path: activationID}).String()
    route := fmt.Sprintf("activations/%s/result", activationID)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s) error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    Debug(DbgInfo, "Sending HTTP request - URL '%s'; req %#v\n", req.URL.String(), req)

    r := new(Response)
    resp, err := s.client.Do(req, &r)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return r, resp, nil

}
