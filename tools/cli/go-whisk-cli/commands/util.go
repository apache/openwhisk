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
    "io/ioutil"
    "sort"
)

type qualifiedName struct {
    namespace   string
    packageName string
    entityName  string
}

func (qName qualifiedName) String() string {
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
func parseQualifiedName(name string) (qName qualifiedName, err error) {

    // If name has a preceding delimiter (/), it contains a namespace. Otherwise the name does not specify a namespace,
    // so default the namespace to the namespace value set in the properties file; if that is not set, use "_"
    if len(name) > 0  && name[0] == '/' {
        parts := strings.Split(name, "/")
        qName.namespace = parts[1]

        if len(parts) > 2 {
            qName.entityName = strings.Join(parts[2:], "/")
        } else {
            qName.entityName = ""
        }
    } else {
        qName.entityName = name

        if Properties.Namespace != "" {
            qName.namespace = Properties.Namespace
        } else {
            qName.namespace = "_"
        }
    }

    whisk.Debug(whisk.DbgInfo, "Qualified entityName: %s\n", qName.entityName)
    whisk.Debug(whisk.DbgInfo, "Qaulified namespace: %s\n", qName.namespace)

    return qName, err
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

func getJSONFromStrings(content []string, keyValueFormat bool) (interface{}, error) {
    var data map[string]interface{}
    var res interface{}

    for i := 0; i < len(content); i++ {
        if err := json.Unmarshal([]byte(content[i]), &data); err != nil {
            whisk.Debug(whisk.DbgError, "Invalid JSON detected for '%s'\n", content[i])
            return whisk.KeyValueArr{}, err
        }

        whisk.Debug(whisk.DbgInfo, "Created map '%v' from '%v'\n", data, content[i])
    }

    if keyValueFormat {
        res = getKeyValueFormattedJSON(data)
    } else {
        res = data
    }

    return res, nil
}

func getKeyValueFormattedJSON(data map[string]interface{}) (whisk.KeyValueArr) {
    var keyValueArr whisk.KeyValueArr

    i := 0

    for key, value := range data {
        keyValue := whisk.KeyValue{
            Key:  key,
            Value: value,
        }
        keyValueArr = append(keyValueArr, keyValue)
        i++
    }

    whisk.Debug(whisk.DbgInfo, "Created key/value format '%v' from '%v'\n", keyValueArr, data)

    return keyValueArr
}

func getFormattedJSON(key string, value string) (string) {
    var res string

    key = getEscapedJSON(key)

    if isValidJSON(value) {
        whisk.Debug(whisk.DbgInfo, "Value '%s' is valid JSON.\n", value)
        res = fmt.Sprintf("{\"%s\": %s}", key, value)
    } else {
        whisk.Debug(whisk.DbgInfo, "Converting value '%s' to a string as it is not valid JSON.\n", value)
        res = fmt.Sprintf("{\"%s\": \"%s\"}", key, value)
    }

    whisk.Debug(whisk.DbgInfo, "Formatted JSON '%s'\n", res)

    return res
}

func getEscapedJSON(value string) (string) {
    value = strings.Replace(value, "\\", "\\\\", -1)
    value = strings.Replace(value, "\"", "\\\"", -1)

    return value
}

func isValidJSON(value string) (bool) {
    var jsonInterface interface{}
    err := json.Unmarshal([]byte(value), &jsonInterface)
    return err == nil
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
    case []whisk.Api:
        printApiList(collection)
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

func printApiList(apis []whisk.Api) {
    fmt.Fprintf(color.Output, "%s\n", boldString("apis"))
    for _, api := range apis {
        fmt.Printf("%s %20s %20s\n", api.ApiName, api.GatewayBasePath, api.GatewayFullPath)
    }
}

func printFullApiList(apis []whisk.Api) {
    fmt.Fprintf(color.Output, "%s\n", boldString("apis"))
    for _, api := range apis {
        printJSON(api)
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
        getValueString(pkg.Annotations, "description"),
        strings.Join(getChildValueStrings(pkg.Annotations, "parameters", "name"), ", "))


    if pkg.Actions != nil {
        for _, action := range pkg.Actions {
            printEntitySummary(fmt.Sprintf("%7s", "action"), getFullName(pkg.Namespace, pkg.Name, action.Name),
                getValueString(action.Annotations, "description"),
                strings.Join(getChildValueStrings(action.Annotations, "parameters", "name"), ", "))
        }
    }

    if pkg.Feeds != nil {
        for _, feed := range pkg.Feeds {
            printEntitySummary(fmt.Sprintf("%7s", "feed  "), getFullName(pkg.Namespace, pkg.Name, feed.Name),
                getValueString(feed.Annotations, "description"),
                strings.Join(getChildValueStrings(feed.Annotations, "parameters", "name"), ", "))
        }
    }
}

func printActionSummary(action *whisk.Action) {
    printEntitySummary(fmt.Sprintf("%6s", "action"),
        getFullName(action.Namespace, "", action.Name),
        getValueString(action.Annotations, "description"),
        strings.Join(getChildValueStrings(action.Annotations, "parameters", "name"), ", "))
}

func printTriggerSummary(trigger *whisk.TriggerFromServer) {
    printEntitySummary(fmt.Sprintf("%7s", "trigger"),
        getFullName(trigger.Namespace, "", trigger.Name),
        getValueString(trigger.Annotations, "description"),
        strings.Join(getChildValueStrings(trigger.Annotations, "parameters", "name"), ", "))
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

func getKeys(keyValueArr whisk.KeyValueArr) ([]string) {
    var res []string

    for i := 0; i < len(keyValueArr); i++ {
        res = append(res, keyValueArr[i].Key)
    }

    sort.Strings(res)
    whisk.Debug(whisk.DbgInfo, "Got keys '%v' from '%v'\n", res, keyValueArr)

    return res
}

func getValue(keyValueArr whisk.KeyValueArr, key string) (interface{}) {
    var res interface{}

    for i := 0; i < len(keyValueArr); i++ {
        if keyValueArr[i].Key == key {
            res = keyValueArr[i].Value
            break;
        }
    }

    whisk.Debug(whisk.DbgInfo, "Got value '%v' from '%v' for key '%s'\n", res, keyValueArr, key)

    return res
}

func getValueString(keyValueArr whisk.KeyValueArr, key string) (string) {
    var value interface{}
    var res string

    value = getValue(keyValueArr, key)
    castedValue, canCast := value.(string)

    if (canCast) {
        res = castedValue
    }

    whisk.Debug(whisk.DbgInfo, "Got string value '%v' for key '%s'\n", res, key)

    return res
}

func getChildValues(keyValueArr whisk.KeyValueArr, key string, childKey string) ([]interface{}) {
    var value interface{}
    var res []interface{}

    value = getValue(keyValueArr, key)

    castedValue, canCast := value.([]interface{})
    if canCast {
        for i := 0; i < len(castedValue); i++ {
            castedValue, canCast := castedValue[i].(map[string]interface{})
            if canCast {
                for subKey, subValue := range castedValue {
                    if subKey == childKey {
                        res = append(res, subValue)
                    }
                }
            }
        }
    }

    whisk.Debug(whisk.DbgInfo, "Got values '%s' from '%v' for key '%s' and child key '%s'\n", res, keyValueArr, key,
        childKey)

    return res
}

func getChildValueStrings(keyValueArr whisk.KeyValueArr, key string, childKey string) ([]string) {
    var keys []interface{}
    var res []string

    keys = getChildValues(keyValueArr, key, childKey)

    for i := 0; i < len(keys); i++ {
        castedValue, canCast := keys[i].(string)
        if (canCast) {
            res = append(res, castedValue)
        }
    }

    sort.Strings(res)
    whisk.Debug(whisk.DbgInfo, "Got values '%s' from '%v' for key '%s' and child key '%s'\n", res, keyValueArr, key,
        childKey)

    return res
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
    if len(host) == 0 {
        errMsg := wski18n.T("An API host must be provided.")
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return nil, whiskErr
    }

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

func readFile(filename string) (string, error) {
    _, err := os.Stat(filename)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Stat(%s) error: %s\n", filename, err)
        errMsg := fmt.Sprintf(
            wski18n.T("File '{{.name}}' is not a valid file or it does not exist: {{.err}}",
                map[string]interface{}{"name": filename, "err": err}))
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_USAGE,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return "", whiskErr
    }

    file, err := ioutil.ReadFile(filename)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.ioutil.ReadFile(%s) error: %s\n", filename, err)
        errMsg := fmt.Sprintf(
            wski18n.T("Unable to read '{{.name}}': {{.err}}",
                map[string]interface{}{"name": filename, "err": err}))
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return "", whiskErr
    }

    return string(file), nil
}
