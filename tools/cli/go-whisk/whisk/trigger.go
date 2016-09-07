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

type TriggerService struct {
    client *Client
}

type Trigger struct {
    Namespace string                `json:"namespace,omitempty"`
    Name      string                `json:"-"`
    Version   string                `json:"version,omitempty"`
    Publish   bool                  `json:"publish,omitempty"`
    ActivationId string             `json:"activationId,omitempty"`
    Annotations *json.RawMessage    `json:"annotations,omitempty"`
    Parameters  *json.RawMessage    `json:"parameters,omitempty"`
    //Limits                        `json:"limits,omitempty"`
}

type TriggerFromServer struct {
    Namespace string                `json:"namespace"`
    Name      string                `json:"name"`
    Version   string                `json:"version"`
    Publish   bool                  `json:"publish"`
    ActivationId string             `json:"activationId,omitempty"`
    Annotations *json.RawMessage    `json:"annotations"`
    Parameters  *json.RawMessage    `json:"parameters"`
    Limits                          `json:"limits"`
}

type TriggerListOptions struct {
    Limit int  `url:"limit"`
    Skip  int  `url:"skip"`
    Docs  bool `url:"docs,omitempty"`
}

func (s *TriggerService) List(options *TriggerListOptions) ([]TriggerFromServer, *http.Response, error) {
    route := "triggers"
    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errStr := wski18n.T("Unable to append options '{{.options}}' to URL route '{{.route}}': {{.err}}",
            map[string]interface{}{"options": fmt.Sprintf("%#v", options), "route": route, "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    var triggers []TriggerFromServer
    resp, err := s.client.Do(req, &triggers)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return triggers, resp, nil

}

func (s *TriggerService) Insert(trigger *Trigger, overwrite bool) (*TriggerFromServer, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    trigger.Name = (&url.URL{Path:  trigger.Name}).String()
    route := fmt.Sprintf("triggers/%s?overwrite=%t", trigger.Name, overwrite)

    routeUrl, err := url.Parse(route)
    if err != nil {
        Debug(DbgError, "url.Parse(%s) error: %s\n", route, err)
        errStr := wski18n.T("Invalid request URL '{{.url}}': {{.err}}",
            map[string]interface{}{"url": route, "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    req, err := s.client.NewRequestUrl("PUT", routeUrl, trigger)
    if err != nil {
        Debug(DbgError, "http.NewRequest(PUT, %s); error: '%s'\n", routeUrl, err)
        errStr := wski18n.T("Unable to create HTTP request for PUT '{{.route}}': {{.err}}",
            map[string]interface{}{"route": routeUrl, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    t := new(TriggerFromServer)
    resp, err := s.client.Do(req, &t)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return t, resp, nil

}

func (s *TriggerService) Get(triggerName string) (*TriggerFromServer, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    triggerName = (&url.URL{Path: triggerName}).String()
    route := fmt.Sprintf("triggers/%s", triggerName)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    t := new(TriggerFromServer)
    resp, err := s.client.Do(req, &t)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return t, resp, nil

}

func (s *TriggerService) Delete(triggerName string) (*TriggerFromServer, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    triggerName = (&url.URL{Path: triggerName}).String()
    route := fmt.Sprintf("triggers/%s", triggerName)

    req, err := s.client.NewRequest("DELETE", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(DELETE, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    t := new(TriggerFromServer)
    resp, err := s.client.Do(req, &t)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return t, resp, nil
}

func (s *TriggerService) Fire(triggerName string, payload *json.RawMessage) (*TriggerFromServer, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    triggerName = (&url.URL{Path: triggerName}).String()
    route := fmt.Sprintf("triggers/%s", triggerName)

    req, err := s.client.NewRequest("POST", route, payload)
    if err != nil {
        Debug(DbgError," http.NewRequest(POST, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    t := new(TriggerFromServer)
    resp, err := s.client.Do(req, &t)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return t, resp, nil
}
