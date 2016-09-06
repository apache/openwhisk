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

type ActionService struct {
    client *Client
}

type Action struct {
    Namespace   string              `json:"namespace,omitempty"`
    Name        string              `json:"name,omitempty"`
    Version     string              `json:"version,omitempty"`
    Publish     bool                `json:"publish"`
    Exec        *Exec               `json:"exec,omitempty"`
    Annotations *json.RawMessage    `json:"annotations,omitempty"`
    Parameters  *json.RawMessage    `json:"parameters,omitempty"`
    Limits      *Limits             `json:"limits,omitempty"`
}

type SentActionPublish struct {
    Namespace   string              `json:"-"`
    Version     string              `json:"-"`
    Publish     bool                `json:"publish"`
    Parameters  *json.RawMessage    `json:"parameters,omitempty"`
    Exec        *Exec               `json:"exec,omitempty"`
    Annotations *json.RawMessage    `json:"annotations,omitempty"`
    Limits      *Limits             `json:"limits,omitempty"`
    Error       string              `json:"error,omitempty"`
    Code        int                 `json:"code,omitempty"`
}

type SentActionNoPublish struct {
    Namespace   string              `json:"-"`
    Version     string              `json:"-"`
    Publish     bool                `json:"publish,omitempty"`
    Parameters  *json.RawMessage    `json:"parameters,omitempty"`
    Exec        *Exec               `json:"exec,omitempty"`
    Annotations *json.RawMessage    `json:"annotations,omitempty"`
    Limits      *Limits             `json:"limits,omitempty"`
    Error       string              `json:"error,omitempty"`
    Code        int                 `json:"code,omitempty"`
}

type Exec struct {
    Kind        string      `json:"kind,omitempty"`
    Code        string      `json:"code"`
    Image       string      `json:"image,omitempty"`
    Init        string      `json:"init,omitempty"`
    Jar         string      `json:"jar,omitempty"`
    Main        string      `json:"main,omitempty"`
    Components  []string    `json:"components,omitempty"`    // List of fully qualified actions
}

type ActionListOptions struct {
    Limit           int  `url:"limit"`
    Skip            int  `url:"skip"`
    Docs            bool `url:"docs,omitempty"`
}

////////////////////
// Action Methods //
////////////////////

func (s *ActionService) List(packageName string, options *ActionListOptions) ([]Action, *http.Response, error) {
    var route string
    var actions []Action

    if (len(packageName) > 0) {
        // Encode resource name as a path (with no query params) before inserting it into the URI
        // This way any '?' chars in the name won't be treated as the beginning of the query params
        packageName = (&url.URL{Path:  packageName}).String()
        route = fmt.Sprintf("actions/%s/", packageName)
    } else {
        route = fmt.Sprintf("actions")
    }

    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := wski18n.T("Unable to add route options '{{.options}}'",
            map[string]interface{}{"options": options})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Action list route with options: %s\n", route)

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: '%s'\n", routeUrl, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": routeUrl, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    resp, err := s.client.Do(req, &actions)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return actions, resp, err
}

func (s *ActionService) Insert(action *Action, sharedSet bool, overwrite bool) (*Action, *http.Response, error) {
    var sentAction interface{}

    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    action.Name = (&url.URL{Path:  action.Name}).String()
    route := fmt.Sprintf("actions/%s?overwrite=%t", action.Name, overwrite)

    if sharedSet {
        sentAction = SentActionPublish{
            Parameters: action.Parameters,
            Exec: action.Exec,
            Publish: action.Publish,
            Annotations: action.Annotations,
            Limits: action.Limits,
        }
    } else {
        sentAction = SentActionNoPublish{
            Parameters: action.Parameters,
            Exec: action.Exec,
            Annotations: action.Annotations,
            Limits: action.Limits,
        }
    }
    Debug(DbgInfo, "Action insert route: %s\n", route)

    req, err := s.client.NewRequest("PUT", route, sentAction)
    if err != nil {
        Debug(DbgError, "http.NewRequest(PUT, %s, %#v) error: '%s'\n", route, err, sentAction)
        errMsg := wski18n.T("Unable to create HTTP request for PUT '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    a := new(Action)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return a, resp, nil
}

func (s *ActionService) Get(actionName string) (*Action, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    actionName = (&url.URL{Path: actionName}).String()
    route := fmt.Sprintf("actions/%s", actionName)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    a := new(Action)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := wski18n.T("Request failure: {{.err}}",  map[string]interface{}{"err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return a, resp, nil
}

func (s *ActionService) Delete(actionName string) (*http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    actionName = (&url.URL{Path: actionName}).String()
    route := fmt.Sprintf("actions/%s", actionName)
    Debug(DbgInfo, "HTTP route: %s\n", route)

    req, err := s.client.NewRequest("DELETE", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(DELETE, %s, nil) error: '%s'\n", route, err)
        errMsg := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }

    a := new(SentActionNoPublish)
    resp, err := s.client.Do(req, a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return resp, whiskErr
    }

    return resp, nil
}

func (s *ActionService) Invoke(actionName string, payload *json.RawMessage, blocking bool) (*Activation, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    actionName = (&url.URL{Path: actionName}).String()
    route := fmt.Sprintf("actions/%s?blocking=%t", actionName, blocking)
    Debug(DbgInfo, "HTTP route: %s\n", route)

    req, err := s.client.NewRequest("POST", route, payload)
    if err != nil {
        Debug(DbgError, "http.NewRequest(POST, %s, %#v) error: '%s'\n", route, payload, err)
        errMsg := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    activation := new(Activation)
    resp, err := s.client.Do(req, &activation)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return activation, resp, whiskErr
    }

    return activation, resp, nil
}
