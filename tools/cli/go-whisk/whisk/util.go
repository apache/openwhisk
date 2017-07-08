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
    "errors"
    "fmt"
    "net/url"
    "reflect"

    "github.com/fatih/color"
    "github.com/google/go-querystring/query"
    "github.com/hokaccha/go-prettyjson"
    "../wski18n"
)

// addOptions adds the parameters in opt as URL query parameters to s.  opt
// must be a struct whose fields may contain "url" tags.
func addRouteOptions(route string, options interface{}) (*url.URL, error) {
    Debug(DbgInfo, "Adding options %+v to route '%s'\n", options, route)
    u, err := url.Parse(route)
    if err != nil {
        Debug(DbgError,"url.Parse(%s) error: %s\n", route, err)
        errStr := wski18n.T("Unable to parse URL '{{.route}}': {{.err}}",
            map[string]interface{}{"route": route, "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    v := reflect.ValueOf(options)
    if v.Kind() == reflect.Ptr && v.IsNil() {
        return u, nil
    }

    qs, err := query.Values(options)
    if err != nil {
        Debug(DbgError,"query.Values(%#v) error: %s\n", options, err)
        errStr := wski18n.T("Unable to process URL query options '{{.options}}': {{.err}}",
            map[string]interface{}{"options": fmt.Sprintf("%#v", options), "err": err})
        werr := MakeWskError(errors.New(errStr), EXITCODE_ERR_GENERAL, DISPLAY_MSG, NO_DISPLAY_USAGE)
        return nil, werr
    }

    u.RawQuery = qs.Encode()
    Debug(DbgInfo,"Returning route options '%s' from input struct %+v\n", u.String(), options)
    return u, nil
}

func printJSON(v interface{}) {
    output, _ := prettyjson.Marshal(v)
    fmt.Fprintln(color.Output, string(output))
}
