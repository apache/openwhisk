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
    "strings"
    "errors"
    "net/url"
    "../wski18n"
)

type RuleService struct {
    client *Client
}

type Rule struct {
    Namespace string `json:"namespace,omitempty"`
    Name      string `json:"name,omitempty"`
    Version   string `json:"version,omitempty"`
    Publish   bool   `json:"publish,omitempty"`

    Status  string `json:"status"`
    Trigger string `json:"trigger"`
    Action  string `json:"action"`
}

type RuleListOptions struct {
    Limit int  `url:"limit"`
    Skip  int  `url:"skip"`
    Docs  bool `url:"docs,omitempty"`
}

func (s *RuleService) List(options *RuleListOptions) ([]Rule, *http.Response, error) {
    route := "rules"
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

    var rules []Rule
    resp, err := s.client.Do(req, &rules)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        errStr := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, resp, werr
    }

    return rules, resp, err
}

func (s *RuleService) Insert(rule *Rule, overwrite bool) (*Rule, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    rule.Name = (&url.URL{Path: rule.Name}).String()
    route := fmt.Sprintf("rules/%s?overwrite=%t", rule.Name, overwrite)

    req, err := s.client.NewRequest("PUT", route, rule)
    if err != nil {
        Debug(DbgError, "http.NewRequest(PUT, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for PUT '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    r := new(Rule)
    resp, err := s.client.Do(req, &r)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        errStr := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, resp, werr
    }

    return r, resp, nil
}

func (s *RuleService) Get(ruleName string) (*Rule, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    ruleName = (&url.URL{Path: ruleName}).String()
    route := fmt.Sprintf("rules/%s", ruleName)

    req, err := s.client.NewRequest("GET", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for GET '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    r := new(Rule)
    resp, err := s.client.Do(req, &r)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        errStr := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, resp, werr
    }

    return r, resp, nil
}

func (s *RuleService) Delete(ruleName string) (*http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    ruleName = (&url.URL{Path: ruleName}).String()
    route := fmt.Sprintf("rules/%s", ruleName)

    req, err := s.client.NewRequest("DELETE", route, nil)
    if err != nil {
        Debug(DbgError, "http.NewRequest(DELETE, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for DELETE '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    resp, err := s.client.Do(req, nil)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        errStr := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resp, werr
    }

    return resp, nil
}

func (s *RuleService) SetState(ruleName string, state string) (*Rule, *http.Response, error) {
    state = strings.ToLower(state)
    if state != "active" && state != "inactive" {
        errStr := wski18n.T("Internal error. Invalid state option '{{.state}}'. Valid options are \"active\" and \"inactive\".",
            map[string]interface{}{"state": state})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, DISPLAY_USAGE)
        return nil, nil, werr
    }

    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    ruleName = (&url.URL{Path: ruleName}).String()
    route := fmt.Sprintf("rules/%s", ruleName)

    ruleState := &Rule{ Status: state }

    req, err := s.client.NewRequest("POST", route, ruleState)
    if err != nil {
        Debug(DbgError, "http.NewRequest(POST, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create HTTP request for POST '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    r := new(Rule)
    resp, err := s.client.Do(req, &r)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        errStr := wski18n.T("Request failure: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_NETWORK, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, resp, werr
    }

    return r, resp, nil
}
