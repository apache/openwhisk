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
    "net/url"
    "errors"
    "strings"
    "reflect"
    "encoding/json"
)

type ActionService struct {
    client *Client
}

type Action struct {
    Namespace   string      `json:"namespace,omitempty"`
    Name        string      `json:"name,omitempty"`
    Version     string      `json:"version,omitempty"`
    Publish     bool        `json:"publish"`
    Exec        *Exec       `json:"exec,omitempty"`
    Annotations             `json:"annotations,omitempty"`
    Parameters  *json.RawMessage `json:"parameters,omitempty"`   // Can be either []KeyValue or []KeyValues (action seq)
    Limits      *Limits     `json:"limits,omitempty"`
}

func (p *Action) GetAnnotationKeyValue(key string) string {
    var val string = ""

    Debug(DbgInfo, "Looking for annotation with key of '%s'\n", key)
    if p.Annotations != nil {
        for i,_ := range p.Annotations {
            Debug(DbgInfo, "Examining annotation %+v\n", p.Annotations[i])
            annotation := p.Annotations[i]
            if k, ok := annotation["key"].(string); ok {
                if k == key {
                    if val, ok := annotation["value"].(string); ok {
                        Debug(DbgInfo, "annotation[%s] = '%s'\n", key, val)
                        if val != "" {
                            return val
                        }
                    } else {
                        Debug(DbgWarn, "Annotation 'value' is not a string type: %s", reflect.TypeOf(annotation["value"]).String())
                    }
                }
            } else {
                Debug(DbgWarn, "Annotation 'key' is not a string type: %s", reflect.TypeOf(annotation["key"]).String())
            }
        }
    }
    return val
}

type SentActionPublish struct {
    Namespace   string      `json:"-"`
    Version     string      `json:"-"`
    Publish     bool        `json:"publish"`
    Parameters  *json.RawMessage `json:"parameters,omitempty"`
    Exec        *Exec       `json:"exec,omitempty"`
    Annotations             `json:"annotations,omitempty"`
    Limits      *Limits     `json:"limits,omitempty"`
    Error       string      `json:"error,omitempty"`
    Code        int         `json:"code,omitempty"`
}

type SentActionNoPublish struct {
    Namespace   string      `json:"-"`
    Version     string      `json:"-"`
    Publish     bool        `json:"publish,omitempty"`
    Parameters  *json.RawMessage `json:"parameters,omitempty"`
    Exec        *Exec       `json:"exec,omitempty"`
    Annotations             `json:"annotations,omitempty"`
    Limits      *Limits     `json:"limits,omitempty"`
    Error       string      `json:"error,omitempty"`
    Code        int         `json:"code,omitempty"`
}

type Exec struct {
    Kind  string `json:"kind,omitempty"`
    Code  string `json:"code,omitempty"`
    Image string `json:"image,omitempty"`
    Init  string `json:"init,omitempty"`
    Jar   string `json:"jar,omitempty"`
    Main  string `json:"main,omitempty"`
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
        packageName = strings.Replace(url.QueryEscape(packageName), "+", " ", -1)
        route = fmt.Sprintf("actions/%s/", packageName)
    } else {
        route = fmt.Sprintf("actions")
    }

    route, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errMsg := fmt.Sprintf("Unable to add route options: %s", options)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }
    Debug(DbgError, "Action list route with options: %s\n", route)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: '%s'\n", route, err)
        errMsg := fmt.Sprintf("Unable to create HTTP request for GET '%s'; error: %s", route, err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    resp, err := s.client.Do(req, &actions)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := fmt.Sprintf("Request failure: %s", err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return actions, resp, err
}

func (s *ActionService) Insert(action *Action, sharedSet bool, overwrite bool) (*Action, *http.Response, error) {
    var sentAction interface{}

    action.Name = strings.Replace(url.QueryEscape(action.Name), "+", " ", -1)
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
        errMsg := fmt.Sprintf("Unable to create HTTP request for PUT '%s'; error: %s", route, err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    a := new(Action)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := fmt.Sprintf("Request failure: %s", err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return a, resp, nil
}

func (s *ActionService) Get(actionName string) (*Action, *http.Response, error) {

    actionName = strings.Replace(url.QueryEscape(actionName), "+", " ", -1)
    route := fmt.Sprintf("actions/%s", actionName)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s, nil) error: '%s'\n", route, err)
        errMsg := fmt.Sprintf("Unable to create HTTP request for GET '%s'; error: %s", route, err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    a := new(Action)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := fmt.Sprintf("Request failure: %s", err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return a, resp, nil
}

func (s *ActionService) Delete(actionName string) (*http.Response, error) {

    actionName = strings.Replace(url.QueryEscape(actionName), "+", " ", -1)
    route := fmt.Sprintf("actions/%s", actionName)
    Debug(DbgInfo, "HTTP route: %s\n", route)

    req, err := s.client.NewRequest("DELETE", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(DELETE, %s, nil) error: '%s'\n", route, err)
        errMsg := fmt.Sprintf("Unable to create HTTP request for DELETE '%s'; error: %s", route, err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, whiskErr
    }

    a := new(SentActionNoPublish)
    resp, err := s.client.Do(req, a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := fmt.Sprintf("Request failure: %s", err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return resp, whiskErr
    }

    return resp, nil
}

func (s *ActionService) Invoke(actionName string, payload *json.RawMessage, blocking bool) (*Activation, *http.Response, error) {

    actionName = strings.Replace(url.QueryEscape(actionName), "+", " ", -1)
    route := fmt.Sprintf("actions/%s?blocking=%t", actionName, blocking)
    Debug(DbgInfo, "HTTP route: %s\n", route)

    req, err := s.client.NewRequest("POST", route, payload)
    if err != nil {
        Debug(DbgError, "http.NewRequest(POST, %s, %#v) error: '%s'\n", route, payload, err)
        errMsg := fmt.Sprintf("Unable to create HTTP request for POST '%s'; error: %s", route, err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, nil, whiskErr
    }

    a := new(Activation)
    resp, err := s.client.Do(req, &a)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        errMsg := fmt.Sprintf("Request failure: %s", err)
        whiskErr := MakeWskErrorFromWskError(errors.New(errMsg), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG,
            NO_DISPLAY_USAGE)
        return nil, resp, whiskErr
    }

    return a, resp, nil
}
