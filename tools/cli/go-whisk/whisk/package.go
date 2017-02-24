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
    "../wski18n"
)

type PackageService struct {
    client *Client
}

type PackageInterface interface {
    GetName() string
}

// Use this struct to represent the package/binding sent from the Whisk server
// Binding is a bool ???MWD20160602 now seeing Binding as a struct???
type Package struct {
    Namespace   string              `json:"namespace,omitempty"`
    Name        string              `json:"name,omitempty"`
    Version     string              `json:"version,omitempty"`
    Publish     *bool               `json:"publish,omitempty"`
    Annotations KeyValueArr         `json:"annotations,omitempty"`
    Parameters  KeyValueArr         `json:"parameters,omitempty"`
    Binding     *Binding            `json:"binding,omitempty"`
    Actions     []Action            `json:"actions,omitempty"`
    Feeds       []Action            `json:"feeds,omitempty"`
}
func (p *Package) GetName() string {
    return p.Name
}

// Use this struct when creating a binding
// Publish is NOT optional; Binding is a namespace/name object, not a bool
type BindingPackage struct {
    Namespace   string              `json:"-"`
    Name        string              `json:"-"`
    Version     string              `json:"version,omitempty"`
    Publish     *bool               `json:"publish,omitempty"`
    Annotations KeyValueArr         `json:"annotations,omitempty"`
    Parameters  KeyValueArr         `json:"parameters,omitempty"`
    Binding                         `json:"binding"`
}
func (p *BindingPackage) GetName() string {
    return p.Name
}

type Binding struct {
    Namespace   string              `json:"namespace,omitempty"`
    Name        string              `json:"name,omitempty"`
}

type BindingUpdates struct {
    Added       []string            `json:"added,omitempty"`
    Updated     []string            `json:"updated,omitempty"`
    Deleted     []string            `json:"deleted,omitempty"`
}

type PackageListOptions struct {
    Public      bool                `url:"public,omitempty"`
    Limit       int                 `url:"limit"`
    Skip        int                 `url:"skip"`
    Since       int                 `url:"since,omitempty"`
    Docs        bool                `url:"docs,omitempty"`
}

func (s *PackageService) List(options *PackageListOptions) ([]Package, *http.Response, error) {
    route := fmt.Sprintf("packages")
    routeUrl, err := addRouteOptions(route, options)
    if err != nil {
        Debug(DbgError, "addRouteOptions(%s, %#v) error: '%s'\n", route, options, err)
        errStr := wski18n.T("Unable to build request URL: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    req, err := s.client.NewRequestUrl("GET", routeUrl, nil, IncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson)
    if err != nil {
        Debug(DbgError, "http.NewRequestUrl(GET, %s, nil, IncludeNamespaceInUrl, AppendOpenWhiskPathPrefix, EncodeBodyAsJson); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create GET HTTP request for '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    var packages []Package
    resp, err := s.client.Do(req, &packages, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return packages, resp, err

}

func (s *PackageService) Get(packageName string) (*Package, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    packageName = (&url.URL{Path: packageName}).String()
    route := fmt.Sprintf("packages/%s", packageName)

    req, err := s.client.NewRequest("GET", route, nil, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "http.NewRequest(GET, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create GET HTTP request for '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    p := new(Package)
    resp, err := s.client.Do(req, &p, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return p, resp, nil

}

func (s *PackageService) Insert(x_package PackageInterface, overwrite bool) (*Package, *http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    packageName := (&url.URL{Path: x_package.GetName()}).String()
    route := fmt.Sprintf("packages/%s?overwrite=%t", packageName, overwrite)

    req, err := s.client.NewRequest("PUT", route, x_package, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "http.NewRequest(PUT, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create PUT HTTP request for '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    p := new(Package)
    resp, err := s.client.Do(req, &p, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return p, resp, nil
}

func (s *PackageService) Delete(packageName string) (*http.Response, error) {
    // Encode resource name as a path (with no query params) before inserting it into the URI
    // This way any '?' chars in the name won't be treated as the beginning of the query params
    packageName = (&url.URL{Path: packageName}).String()
    route := fmt.Sprintf("packages/%s", packageName)

    req, err := s.client.NewRequest("DELETE", route, nil, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "http.NewRequest(DELETE, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create DELETE HTTP request for '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    resp, err := s.client.Do(req, nil, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return resp, err
    }

    return resp, nil
}

func (s *PackageService) Refresh() (*BindingUpdates, *http.Response, error) {
    route := "packages/refresh"

    req, err := s.client.NewRequest("POST", route, nil, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "http.NewRequest(POST, %s); error: '%s'\n", route, err)
        errStr := wski18n.T("Unable to create POST HTTP request for '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    updates := &BindingUpdates{}
    resp, err := s.client.Do(req, updates, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error: '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    return updates, resp, nil
}
