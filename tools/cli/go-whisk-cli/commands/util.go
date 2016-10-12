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

package commands

import (
    "errors"
    "fmt"
    "strings"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/fatih/color"
    //prettyjson "github.com/hokaccha/go-prettyjson"  // See prettyjson comment below
    "archive/tar"
    "io"
    "os"
    "compress/gzip"
    "archive/zip"
    "encoding/json"
    "net/url"
)

type QualifiedName struct {
    namespace   string
    packageName string
    entityName  string
}

func (qName QualifiedName) String() string {
    output := []string{}

    if len(qName.namespace) > 0 {
        output = append(output, "/", qName.namespace, "/")
    }
    if len(qName.packageName) > 0 {
        output = append(output, qName.packageName, "/")
    }
    output = append(output, qName.entityName)

    return strings.Join(output, "")
}

/*
Parse a (possibly fully qualified) resource name into namespace and name components. If the given qualified name isNone,
then this is a default qualified name and it is resolved from properties. If the namespace is missing from the qualified
name, the namespace is also resolved from the property file.

Return a qualifiedName struct

Examples:
      foo => qName {namespace: "_", entityName: foo}
      pkg/foo => qName {namespace: "_", entityName: pkg/foo}
      /ns/foo => qName {namespace: ns, entityName: foo}
      /ns/pkg/foo => qName {namespace: ns, entityName: pkg/foo}
*/
func parseQualifiedName(name string) (QualifiedName) {
    var qualifedName QualifiedName

    // If name has a preceding delimiter (/), it contains a namespace. Otherwise the name does not specify a namespace,
    // so default the namespace to the namespace value set in the properties file; if that is not set, use "_"
    if len(name) > 0  && name[0] == '/' {
        parts := strings.Split(name, "/")
        qualifedName.namespace = parts[1]

        if len(qualifedName.namespace) == 0 {
            qualifedName.namespace = getNamespace()
        }

        if len(parts) > 2 {
            qualifedName.entityName = strings.Join(parts[2:], "/")
        }
    } else {
        qualifedName.entityName = name
        qualifedName.namespace = getNamespace()
    }

    whisk.Debug(whisk.DbgInfo, "Qualified entityName '%s'\n", qualifedName.entityName)
    whisk.Debug(whisk.DbgInfo, "Qaulified namespace '%s'\n", qualifedName.namespace)

    return qualifedName
}

func getNamespace() (string) {
    namespace := "_"

    if Properties.Namespace != "" {
        namespace = Properties.Namespace
    }

    return namespace
}

/*
Return a fully qualified name given a (possibly fully qualified) resource name and optional namespace.

Examples:
      (foo, None) => /_/foo
      (pkg/foo, None) => /_/pkg/foo
      (foo, ns) => /ns/foo
      (/ns/pkg/foo, None) => /ns/pkg/foo
      (/ns/pkg/foo, otherns) => /ns/pkg/foo
*/
func getQualifiedName(name string, namespace string) (qualifiedName string) {
    if len(name) > 0 && name[0] == '/' {
        return name
    } else if len(namespace) > 0 && namespace[0] == '/' {
        return fmt.Sprintf("%s/%s", namespace, name)
    } else {
        if len(namespace) == 0 {
            namespace = Properties.Namespace
        }
        return fmt.Sprintf("/%s/%s", namespace, name)
    }
}

func csvToQualifiedActions(artifacts string) ([]string) {
    var res []string
    actions := strings.Split(artifacts, ",")
    for i := 0; i < len(actions); i++ {
        res = append(res, getQualifiedName(actions[i], Properties.Namespace))
    }

    return res
}

func isValidJSON(data string) (isValid bool, err error) {
    var jsonInterface interface{}
    err = json.Unmarshal([]byte(data), &jsonInterface)
    isValid = (err == nil)

    return isValid, err
}

func getJSONFromArguments(args []string, keyValueFormat bool) (*json.RawMessage, error) {
    var res string

    if len(args) == 0 {
        whisk.Debug(whisk.DbgInfo, "getJSONFromArguments(): No arguments provided.\n")
        return nil, nil
    }

    if len(args) % 2 != 0 {
        whisk.Debug(whisk.DbgError, "Number of arguments (%d) must be an even number; args: %#v\n", len(args), args)
        err := whisk.MakeWskError(
            errors.New(wski18n.T("Arguments must be comma separated, and must be quoted if they contain spaces.")),
            whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE )
        return nil, err
    }

    for i := 0; i < len(args); i += 2 {
        key, value, err := getJSONKeyValueFromArguments(args[i], args[i + 1])
        if (err != nil) {
            whisk.Debug(whisk.DbgInfo, "The arguments are not valid JSON (key '%s' value '%s'): %s\n", args[i], args[i + 1], err)
            errMsg := fmt.Sprintf(
                wski18n.T("Invalid arguments: {{.err}}",
                    map[string]interface{}{"err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return nil, whiskErr
        }

        // At this point the key is a JSON string (already quoted), and the value is valid JSON
        // as well (quoted if it's a JSON string)
        if keyValueFormat {
            res = res + fmt.Sprintf("{\"key\": %s, \"value\": %s}", key, value)
        } else {
            res = res + fmt.Sprintf("%s: %s", key, value)
        }

        if i < len(args) - 3 {
            res = fmt.Sprintf("%s, ", res)
        }
    }

    if keyValueFormat {
        res = fmt.Sprintf("[%s]", res)
    } else {
        res = fmt.Sprintf("{%s}", res)
    }

    data := []byte(res)

    whisk.Debug(whisk.DbgInfo, "getJSONFromArguments: JSON: %s\n", res)
    return (*json.RawMessage)(&data), nil
}

/*
 * Take two strings and ensure they are valid JSON.  Convert as needed
 * key   - Not expected to be valid JSON at all.  This is just a string
 * value - This is expected to be ether a JSON string (without quotes) or
 *         or a valid JSON object, array, quoted string, number, boolean, or null
 */
func getJSONKeyValueFromArguments(key string, value string) (jsonKey string, jsonVal string, err error) {
    //key = getEscapedString(key)
    if jsonKey, err = makeJsonString(key); err != nil {
        whisk.Debug(whisk.DbgInfo, "The key `%s` is invalid: %s\n", key, err)
        errMsg := fmt.Sprintf(
            wski18n.T("The argument `{{.arg}}` is invalid: {{.err}}",
                map[string]interface{}{"arg": key, "err": err}))
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return jsonKey, jsonVal, whiskErr
    }

    isValid, err := isValidJSON(value)
    if (len(value) == 0 || !isValid) {
        if (appearsToBeJsonArray(value) || appearsToBeJsonObject(value)) {
            whisk.Debug(whisk.DbgInfo, "The value `%s` is invalid JSON: %s\n", value, err)
            errMsg := fmt.Sprintf(
                wski18n.T("The argument `{{.arg}}` is invalid JSON: {{.err}}",
                    map[string]interface{}{"arg": value, "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return jsonKey, jsonVal, whiskErr
        } else {
            whisk.Debug(whisk.DbgInfo, "Value '%s' is invalid JSON (err: %s) but does not appear to be JSON object or array, so assuming it is a JSON string\n", value, err)
            jsonVal = fmt.Sprintf("\"%s\"", value)
        }
    } else {
        jsonVal = value
    }

    return jsonKey, jsonVal, nil
}

/*
 * Best effort attempt to convert a non-JSON string into a JSON string
 * See json.org for JSON string definition
 * 1. Escape all \ characters in the string, even double \\
 * 2. Escape all " (double quote) characters in the string
 * 3. Surround the string in double quotes
 */
func makeJsonString(nonJsonStr string) (jsonStr string, whiskErr error) {
    jsonStr = strings.Replace(nonJsonStr, "\\", "\\\\", -1)
    jsonStr = strings.Replace(jsonStr, "\"", "\\\"", -1)
    jsonStr = fmt.Sprintf("\"%s\"", jsonStr)

    // Confirm that this is now a valid JSON string; if not return an error
    if isValid, err := isValidJSON(jsonStr); !isValid {
        whisk.Debug(whisk.DbgInfo, "Unable to convert `%s` into JSON string: %s\n", nonJsonStr, err)
        errMsg := fmt.Sprintf(
            wski18n.T("The argument `{{.arg}}` is invalid: {{.err}}",
                map[string]interface{}{"arg": nonJsonStr, "err": err}))
        whiskErr = whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return jsonStr, whiskErr
    }

    return jsonStr, nil
}

func appearsToBeJsonArray(jsonStr string) (isArray bool) {
    if (len(jsonStr) > 0 && strings.HasPrefix(jsonStr, "[") && strings.HasSuffix(jsonStr, "]")) {
        isArray = true
    }
    return isArray
}

func appearsToBeJsonObject(jsonStr string) (isObject bool) {
    if (len(jsonStr) > 0 && strings.HasPrefix(jsonStr, "{") && strings.HasSuffix(jsonStr, "}")) {
        isObject = true
    }
    return isObject
}


var boldString = color.New(color.Bold).SprintFunc()

func printList(collection interface{}) {
    switch collection := collection.(type) {
    case []whisk.Action:
        printActionList(collection)
    case []whisk.TriggerFromServer:
        printTriggerList(collection)
    case []whisk.Package:
        printPackageList(collection)
    case []whisk.Rule:
        printRuleList(collection)
    case []whisk.Namespace:
        printNamespaceList(collection)
    case []whisk.Activation:
        printActivationList(collection)
    }
}

func printFullList(collection interface{}) {
    switch collection := collection.(type) {
    case []whisk.Action:

    case []whisk.Trigger:

    case []whisk.Package:

    case []whisk.Rule:

    case []whisk.Namespace:

    case []whisk.Activation:
        printFullActivationList(collection)
    }
}

func printSummary(collection interface{}) {
    switch collection := collection.(type) {
    case *whisk.Action:
        printActionSummary(collection)
    case *whisk.TriggerFromServer:
        printTriggerSummary(collection)
    case *whisk.Package:
        printPackageSummary(collection)
    case *whisk.Rule:

    case *whisk.Namespace:

    case *whisk.Activation:
    }
}

func printActionList(actions []whisk.Action) {
    fmt.Fprintf(color.Output, "%s\n", boldString("actions"))
    for _, action := range actions {
        publishState := wski18n.T("private")
        if action.Publish {
            publishState = wski18n.T("shared")
        }
        fmt.Printf("%-70s %s\n", fmt.Sprintf("/%s/%s", action.Namespace, action.Name), publishState)
    }
}

func printTriggerList(triggers []whisk.TriggerFromServer) {
    fmt.Fprintf(color.Output, "%s\n", boldString("triggers"))
    for _, trigger := range triggers {
        publishState := wski18n.T("private")
        if trigger.Publish {
            publishState = wski18n.T("shared")
        }
        fmt.Printf("%-70s %s\n", fmt.Sprintf("/%s/%s", trigger.Namespace, trigger.Name), publishState)
    }
}

func printPackageList(packages []whisk.Package) {
    fmt.Fprintf(color.Output, "%s\n", boldString("packages"))
    for _, xPackage := range packages {
        publishState := wski18n.T("private")
        if xPackage.Publish {
            publishState = wski18n.T("shared")
        }
        fmt.Printf("%-70s %s\n", fmt.Sprintf("/%s/%s", xPackage.Namespace, xPackage.Name), publishState)
    }
}

func printRuleList(rules []whisk.Rule) {
    fmt.Fprintf(color.Output, "%s\n", boldString("rules"))
    for _, rule := range rules {
        publishState := wski18n.T("private")
        if rule.Publish {
            publishState = wski18n.T("shared")
        }
        fmt.Printf("%-70s %s\n", fmt.Sprintf("/%s/%s", rule.Namespace, rule.Name), publishState)
    }
}

func printNamespaceList(namespaces []whisk.Namespace) {
    fmt.Fprintf(color.Output, "%s\n", boldString("namespaces"))
    for _, namespace := range namespaces {
        fmt.Printf("%s\n", namespace.Name)
    }
}

func printActivationList(activations []whisk.Activation) {
    fmt.Fprintf(color.Output, "%s\n", boldString("activations"))
    for _, activation := range activations {
        fmt.Printf("%s %20s\n", activation.ActivationID, activation.Name)
    }
}

func printFullActivationList(activations []whisk.Activation) {
    fmt.Fprintf(color.Output, "%s\n", boldString("activations"))
    for _, activation := range activations {
        printJSON(activation)
    }
}

func printActivationLogs(logs []string) {
    for _, log := range logs {
        fmt.Printf("%s\n", log)
    }
}

func printArrayContents(arrStr []string) {
    for _, str := range arrStr {
        fmt.Printf("%s\n", str)
    }
}

func printPackageSummary(pkg *whisk.Package) {
    printEntitySummary(fmt.Sprintf("%7s", "package"), getFullName(pkg.Namespace, pkg.Name, ""),
        getValueFromAnnotations(pkg.Annotations, "description"), getParamNamesFromAnnotations(pkg.Annotations))


    if pkg.Actions != nil {
        for _, a := range pkg.Actions {
            printEntitySummary(fmt.Sprintf("%7s", "action"), getFullName(pkg.Namespace, pkg.Name, a.Name),
                getValueFromAnnotations(a.Annotations, "description"), getParamNamesFromAnnotations(a.Annotations))
        }
    }

    if pkg.Feeds != nil {
        for _, f := range pkg.Feeds {
            printEntitySummary(fmt.Sprintf("%7s", "feed  "), getFullName(pkg.Namespace, pkg.Name, f.Name),
                getValueFromAnnotations(f.Annotations, "description"), getParamNamesFromAnnotations(f.Annotations))
        }
    }
}

func printActionSummary(action *whisk.Action) {
    printEntitySummary(fmt.Sprintf("%6s", "action"),
        getFullName(action.Namespace, "", action.Name),
        getValueFromAnnotations(action.Annotations, "description"),
        getParamNamesFromAnnotations(action.Annotations))
}

func printTriggerSummary(trigger *whisk.TriggerFromServer) {
    printEntitySummary(fmt.Sprintf("%7s", "trigger"),
        getFullName(trigger.Namespace, "", trigger.Name),
        getValueFromAnnotations(trigger.Annotations, "description"),
        getParamNamesFromAnnotations(trigger.Annotations))
}

func printRuleSummary(rule *whisk.Rule) {
    fmt.Fprintf(color.Output, "%s %s\n", boldString(fmt.Sprintf("%4s", "rule")),
        getFullName(rule.Namespace, "", rule.Name))
    fmt.Fprintf(color.Output, "   (%s: %s)\n", boldString(wski18n.T("status")), rule.Status)
}

func printEntitySummary(entityType string, fullName string, description string, params string) {
    if len(description) > 0 {
        fmt.Fprintf(color.Output, "%s %s: %s\n", boldString(entityType), fullName, description)
    } else {
        fmt.Fprintf(color.Output, "%s %s\n", boldString(entityType), fullName)
    }

    if len(params) > 0 {
        fmt.Fprintf(color.Output, "   (%s: %s)\n", boldString(wski18n.T("parameters")), params)
    }
}

func getFullName(namespace string, packageName string, entityName string) (string) {
    var fullName string

    if len(namespace) > 0 && len(packageName) > 0 && len(entityName) > 0 {
        fullName = fmt.Sprintf("/%s/%s/%s", namespace, packageName, entityName)
    } else if len(namespace) > 0 && len(packageName) > 0 {
        fullName = fmt.Sprintf("/%s/%s", namespace, packageName)
    } else if len(namespace) > 0 && len(entityName) > 0 {
        fullName = fmt.Sprintf("/%s/%s", namespace, entityName)
    } else if len(namespace) > 0 {
        fullName = fmt.Sprintf("/%s", namespace)
    }

    return fullName
}

type KeyStringValue struct {
    Key     string
    Value   string
}

type KeyRawValue struct {
    Key     string
    Value   *json.RawMessage
}

type Param struct {
    Name string
}

func getParamNamesFromAnnotations(rawJSON *json.RawMessage) (string) {
    var annotations []KeyRawValue
    var parameters []Param
    var res []string
    var paramNames string

    paramNames = getValueFromAnnotations(rawJSON, "parameters")

    if len(paramNames) > 0 {
        res = append(res, getValueFromAnnotations(rawJSON, "parameters"))
    }

    json.Unmarshal([]byte(*rawJSON), &annotations)

    for _, annotation := range annotations {
        if annotation.Key == "parameters" {
            json.Unmarshal([]byte(*annotation.Value), &parameters)

            for _, param := range parameters {
                res = append(res, param.Name)
            }
        }
    }

    paramNames = strings.Join(res, ", ")

    whisk.Debug(whisk.DbgInfo, "Got parameter names '%s' from annotations '%s'\n", paramNames,
        string(*rawJSON))

    return paramNames
}

func getValueFromAnnotations(rawJSON *json.RawMessage, key string) (string) {
    var annotations []KeyStringValue
    var value string

    json.Unmarshal([]byte(*rawJSON), &annotations)

    for _, annotation := range annotations {
        if annotation.Key == key {
            value = annotation.Value
            break
        }
    }

    whisk.Debug(whisk.DbgInfo, "Got value '%s' for key '%s' from annotations '%s'\n", value, key, string(*rawJSON))

    return value
}

func logoText() string {
    logo := `
        ____      ___                   _    _ _     _     _
       /\   \    / _ \ _ __   ___ _ __ | |  | | |__ (_)___| | __
  /\  /__\   \  | | | | '_ \ / _ \ '_ \| |  | | '_ \| / __| |/ /
 /  \____ \  /  | |_| | |_) |  __/ | | | |/\| | | | | \__ \   <
 \   \  /  \/    \___/| .__/ \___|_| |_|__/\__|_| |_|_|___/_|\_\
  \___\/ tm           |_|
`

    return logo
}

func printJSON(v interface{}, stream ...io.Writer) {
    // Can't use prettyjson util issue  https://github.com/hokaccha/go-prettyjson/issues/1 is fixed
    //output, _ := prettyjson.Marshal(v)
    //
    //if len(stream) > 0 {
    //    fmt.Fprintf(stream[0], string(output))
    //} else {
    //    fmt.Fprintf(color.Output, string(output))
    //}
    printJsonNoColor(v, stream...)
}

// Same as printJSON, but with coloring disabled.
func printJsonNoColor(v interface{}, stream ...io.Writer) {
    output, err := json.MarshalIndent(v, "", "    ")

    if err != nil {
        whisk.Debug(whisk.DbgError, "json.MarshalIndent() failure: %s\n", err)
    }

    if len(stream) > 0 {
        fmt.Fprintf(stream[0], "%s\n", string(output))
    } else {
        fmt.Fprintf(os.Stdout, "%s\n", string(output))
    }
}

func unpackGzip(inpath string, outpath string) error {
    // Make sure the target file does not exist
    if _, err := os.Stat(outpath); err == nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' exists\n", outpath)
        errStr := fmt.Sprintf(
            wski18n.T("The file {{.name}} already exists.  Delete it and retry.",
                map[string]interface{}{"name": outpath}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    // Make sure the input file exists
    if _, err := os.Stat(inpath); err != nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' does not exist\n", inpath)
        errStr := fmt.Sprintf(
            wski18n.T("The file '{{.name}}' does not exist.",
                map[string]interface{}{"name": inpath}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    unGzFile, err := os.Create(outpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", outpath, err)
        errStr := fmt.Sprintf(
            wski18n.T("Error creating unGzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": outpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer unGzFile.Close()

    gzFile, err := os.Open(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Open(%s) failed: %s\n", inpath, err)
        errStr := fmt.Sprintf(
            wski18n.T("Error opening Gzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer gzFile.Close()

    gzReader, err := gzip.NewReader(gzFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "gzip.NewReader() failed: %s\n", err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    _, err = io.Copy(unGzFile, gzReader)
    if err != nil {
        whisk.Debug(whisk.DbgError, "io.Copy() failed: %s\n", err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    return nil
}

func unpackZip(inpath string) error {
    // Make sure the input file exists
    if _, err := os.Stat(inpath); err != nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' does not exist\n", inpath)
        errStr := fmt.Sprintf(
            wski18n.T("The file '{{.name}}' does not exist.",
                map[string]interface{}{"name": inpath}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    zipFileReader, err := zip.OpenReader(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "zip.OpenReader(%s) failed: %s\n", inpath, err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to opens '{{.name}}' for unzipping: {{.err}}",
                map[string]interface{}{"name": inpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer zipFileReader.Close()

    // Loop through the files in the zipfile
    for _, item := range zipFileReader.File {
        itemName := item.Name
        itemType := item.Mode()

        whisk.Debug(whisk.DbgInfo, "file item - %#v\n", item)

        if itemType.IsDir() {
            if err := os.MkdirAll(item.Name, item.Mode()); err != nil {
                whisk.Debug(whisk.DbgError, "os.MkdirAll(%s, %d) failed: %s\n", item.Name, item.Mode(), err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to create directory '{{.dir}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"dir": item.Name, "name": inpath, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        if itemType.IsRegular() {
            unzipFile, err := item.Open()
            defer unzipFile.Close()
            if err != nil {
                whisk.Debug(whisk.DbgError, "'%s' Open() failed: %s\n", item.Name, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to open zipped file '{{.file}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            targetFile, err := os.Create(itemName)
            if err != nil {
                whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", itemName, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to create file '{{.file}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            if _, err := io.Copy(targetFile, unzipFile); err != nil {
                whisk.Debug(whisk.DbgError, "io.Copy() of '%s' failed: %s\n", itemName, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": itemName, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }
    }

    return nil
}

func unpackTar(inpath string) error {

    // Make sure the input file exists
    if _, err := os.Stat(inpath); err != nil {
        whisk.Debug(whisk.DbgError, "os.Stat reports file '%s' does not exist\n", inpath)
        errStr := fmt.Sprintf(
            wski18n.T("The file '{{.name}}' does not exist.",
                map[string]interface{}{"name": inpath}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    tarFileReader, err := os.Open(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Open(%s) failed: %s\n", inpath, err)
        errStr := fmt.Sprintf(
            wski18n.T("Error opening tar file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer tarFileReader.Close()

    // Loop through the files in the tarfile
    tReader := tar.NewReader(tarFileReader)
    for {
        item, err := tReader.Next()
        if err == io.EOF {
            whisk.Debug(whisk.DbgError, "EOF reach during untar\n")
            break  // end of tar
        }
        if err != nil {
            whisk.Debug(whisk.DbgError, "tReader.Next() failed: %s\n", err)
            errStr := fmt.Sprintf(
                wski18n.T("Error reading tar file '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": inpath, "err": err}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "tar file item - %#v\n", item)
        switch item.Typeflag {
        case tar.TypeDir:
            if err := os.MkdirAll(item.Name, os.FileMode(item.Mode)); err != nil {
                whisk.Debug(whisk.DbgError, "os.MkdirAll(%s, %d) failed: %s\n", item.Name, item.Mode, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to create directory '{{.dir}}' while untarring '{{.name}}': {{.err}}",
                        map[string]interface{}{"dir": item.Name, "name": inpath, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        case tar.TypeReg:
            untarFile, err := os.Create(item.Name)
            defer untarFile.Close()
            if err != nil {
                whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", item.Name, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to create file '{{.file}}' while untarring '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            if _, err := io.Copy(untarFile, tReader); err != nil {
                whisk.Debug(whisk.DbgError, "io.Copy() of '%s' failed: %s\n", item.Name, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to untar file '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": item.Name, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        default:
            whisk.Debug(whisk.DbgError, "Unexpected tar file type of %q\n", item.Typeflag)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to untar '{{.name}}' due to unexpected tar file type\n",
                    map[string]interface{}{"name": item.Name}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}

func checkArgs(args []string, minimumArgNumber int, maximumArgNumber int, commandName string,
    requiredArgMsg string) (*whisk.WskError) {
        exactlyOrAtLeast := wski18n.T("exactly")
        exactlyOrNoMoreThan := wski18n.T("exactly")

    if (minimumArgNumber != maximumArgNumber) {
        exactlyOrAtLeast = wski18n.T("at least")
        exactlyOrNoMoreThan = wski18n.T("no more than")
    }

    if len(args) < minimumArgNumber {
        whisk.Debug(whisk.DbgError, fmt.Sprintf("%s command must have %s %d argument(s)\n", commandName,
            exactlyOrAtLeast, minimumArgNumber))
        errMsg := wski18n.T("Invalid argument(s). {{.required}}", map[string]interface{}{"required": requiredArgMsg})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    } else if len(args) > maximumArgNumber {
        whisk.Debug(whisk.DbgError, fmt.Sprintf("%s command must have %s %d argument(s)\n", commandName,
            exactlyOrNoMoreThan, maximumArgNumber))
        errMsg := wski18n.T("Invalid argument(s): {{.args}}. {{.required}}",
            map[string]interface{}{"args": strings.Join(args[maximumArgNumber:], ", "), "required": requiredArgMsg})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    } else {
        return nil
    }
}

func getURLBase(host string) (*url.URL, error)  {
    urlBase := fmt.Sprintf("%s/api/", host)
    url, err := url.Parse(urlBase)

    if len(url.Scheme) == 0 || len(url.Host) == 0 {
        urlBase = fmt.Sprintf("https://%s/api/", host)
        url, err = url.Parse(urlBase)
    }

    return url, err
}

func normalizeNamespace(namespace string) (string) {
    if (namespace == "_") {
        namespace = wski18n.T("default")
    }

    return namespace
}

func getClientNamespace() (string) {
    return normalizeNamespace(client.Config.Namespace)
}
