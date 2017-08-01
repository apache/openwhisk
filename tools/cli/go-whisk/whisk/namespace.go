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
    "errors"
    "../wski18n"
)

type Namespace struct {
    Name                string  `json:"name"`
    Contents                    `json:"contents,omitempty"`
}

type Contents struct {
    Actions  []Action       `json:"actions"`
    Packages []Package      `json:"packages"`
    Triggers []Trigger      `json:"triggers"`
    Rules    []Rule         `json:"rules"`
}

type NamespaceService struct {
    client *Client
}

// get a list of available namespaces
func (s *NamespaceService) List() ([]Namespace, *http.Response, error) {
    // make a request to c.BaseURL / namespaces

    // Create the request against the namespaces resource
    s.client.Config.Namespace = ""
    route := ""
    req, err := s.client.NewRequest("GET", route, nil, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "s.client.NewRequest(GET) error: %s\n", err)
        errStr := wski18n.T("Unable to create HTTP request for GET: {{.err}}",
            map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, nil, werr
    }

    var namespaceNames []string
    resp, err := s.client.Do(req, &namespaceNames, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return nil, resp, err
    }

    var namespaces []Namespace
    for _, nsName := range namespaceNames {
        ns := Namespace{
            Name: nsName,
        }
        namespaces = append(namespaces, ns)
    }

    Debug(DbgInfo, "Returning []namespaces: %#v\n", namespaces)
    return namespaces, resp, nil
}

func (s *NamespaceService) Get(namespace string) (*Namespace, *http.Response, error) {

    if len(namespace) == 0 {
        namespace = s.client.Config.Namespace
    }

    s.client.Namespace = namespace
    resNamespace := &Namespace{
        Name: namespace,
    }

    req, err := s.client.NewRequest("GET", "", nil, IncludeNamespaceInUrl)
    if err != nil {
        Debug(DbgError, "s.client.NewRequest(GET) error: %s\n", err)
        errStr := wski18n.T("Unable to create HTTP request for GET: {{.err}}", map[string]interface{}{"err": err})
        werr := MakeWskErrorFromWskError(errors.New(errStr), err, EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return resNamespace, nil, werr
    }

    resp, err := s.client.Do(req, &resNamespace.Contents, ExitWithSuccessOnTimeout)
    if err != nil {
        Debug(DbgError, "s.client.Do() error - HTTP req %s; error '%s'\n", req.URL.String(), err)
        return resNamespace, resp, err
    }

    Debug(DbgInfo, "Returning namespace: %#v\n", resNamespace)

    return resNamespace, resp, nil
}
