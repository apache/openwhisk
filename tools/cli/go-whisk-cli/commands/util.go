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

package commands

import (
    "bufio"
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
    "io/ioutil"
    "sort"
    "reflect"
    "bytes"
    "regexp"
)

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

    whisk.Debug(whisk.DbgInfo, "Convert content to JSON: %#v\n", content)

    for i := 0; i < len(content); i++ {
        dc := json.NewDecoder(strings.NewReader(content[i]))
        dc.UseNumber()
        if err := dc.Decode(&data); err!=nil {
            whisk.Debug(whisk.DbgError, "Invalid JSON detected for '%s' \n", content[i])
            return whisk.KeyValueArr{} , err
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

    for key, value := range data {
        keyValue := whisk.KeyValue{
            Key:  key,
            Value: value,
        }
        keyValueArr = append(keyValueArr, keyValue)
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

type Sortables []whisk.Sortable
// Uses quickSort to sort commands based on their compare methods
// Param: Takes in a array of Sortable interfaces which contains a specific command
func Swap(sortables Sortables, i, j int) { sortables[i], sortables[j] = sortables[j], sortables[i] }

func toPrintable(sortable []whisk.Sortable) []whisk.Printable{
    sortedPrintable := make([]whisk.Printable, len(sortable), len(sortable))
    for i := range sortable {
        sortedPrintable[i] = sortable[i].(whisk.Printable)
    }
    return sortedPrintable
}

// Prints the parameters/list for wsk xxxx list
// Identifies type and then copies array into an array of interfaces(Sortable) to be sorted and printed
// Param: Takes in an interace which contains an array of a command(Ex: []Action)
func printList(collection interface{}, sortByName bool) {
    var commandToSort []whisk.Sortable
    switch collection := collection.(type){
    case []whisk.Action:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.Trigger:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.Package:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.Rule:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.Namespace:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.Activation:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.ApiFilteredList:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    case []whisk.ApiFilteredRow:
        for i := range collection {
            commandToSort = append(commandToSort, collection[i])
        }
    }
    if sortByName && len(commandToSort) > 0 {
        quickSort(commandToSort, 0, len(commandToSort)-1)
    }
    printCommandsList(toPrintable(commandToSort), makeDefaultHeader(collection))
}

func quickSort(toSort Sortables, left int, right int) {
    low := left
    high := right
    pivot := toSort[(left + right) / 2]

    for low <= high {
        for toSort[low].Compare(pivot) { low++ }
        for pivot.Compare(toSort[high]) { high-- }
        if low <= high {
            Swap(toSort, low, high)
            low++
            high--
        }
    }
    if left < high { quickSort(toSort, left, high) }
    if low < right { quickSort(toSort, low, right) }
}

// makeDefaultHeader(collection) returns the default header to be used in case
//      the list to be printed is empty.
func makeDefaultHeader(collection interface{}) string {
    defaultHeader := reflect.TypeOf(collection).String()
    defaultHeader = strings.ToLower(defaultHeader[8:] + "s")    // Removes '[]whisk.' from `[]whisk.ENTITY_TYPE`
    if defaultHeader == "apifilteredrows" {
        defaultHeader = fmt.Sprintf("%-30s %7s %20s  %s", "Action", "Verb", "API Name", "URL")
    } else if defaultHeader == "apifilteredlists" {
        defaultHeader = ""
    }
    return defaultHeader
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
    case *whisk.Trigger:
        printTriggerSummary(collection)
    case *whisk.Package:
        printPackageSummary(collection)
    case *whisk.Rule:

    case *whisk.Namespace:

    case *whisk.Activation:
    }
}

// Used to print Action, Tigger, Package, and Rule lists
// Param: Takes in a array of Printable interface, and the name of the command
//          being sent to it
// **Note**: The name sould be an empty string for APIs.
func printCommandsList(commands []whisk.Printable, defaultHeader string) {
    if len(commands) != 0 {
        fmt.Fprint(color.Output, boldString(commands[0].ToHeaderString()))
        for i := range commands {
            fmt.Print(commands[i].ToSummaryRowString())
        }
    } else {
        fmt.Fprintf(color.Output, "%s\n", boldString(defaultHeader))
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
        if (flags.activation.strip){
            fmt.Printf("%s\n", log[39:])
        } else {
            fmt.Printf("%s\n", log)
        }

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
        strings.Join(getParamUnion(pkg.Annotations, pkg.Parameters, "name"), ", "))


    if pkg.Actions != nil {
        for _, action := range pkg.Actions {
            paramUnion := getParamUnion(action.Annotations, action.Parameters, "name")
            printEntitySummary(fmt.Sprintf("%7s", "action"), getFullName(pkg.Namespace, pkg.Name, action.Name),
                getValueString(action.Annotations, "description"),
                strings.Join(paramUnion, ", "))
        }
    }

    if pkg.Feeds != nil {
        for _, feed := range pkg.Feeds {
            printEntitySummary(fmt.Sprintf("%7s", "feed  "), getFullName(pkg.Namespace, pkg.Name, feed.Name),
                getValueString(feed.Annotations, "description"),
                strings.Join(getParamUnion(feed.Annotations, feed.Parameters, "name"), ", "))
        }
    }
}

func printActionSummary(action *whisk.Action) {
    paramUnion := getParamUnion(action.Annotations, action.Parameters, "name")
    printEntitySummary(fmt.Sprintf("%6s", "action"),
        getFullName(action.Namespace, "", action.Name),
        getValueString(action.Annotations, "description"),
        strings.Join(paramUnion, ", "))
}

func printTriggerSummary(trigger *whisk.Trigger) {
    printEntitySummary(fmt.Sprintf("%7s", "trigger"),
        getFullName(trigger.Namespace, "", trigger.Name),
        getValueString(trigger.Annotations, "description"),
        strings.Join(getParamUnion(trigger.Annotations, trigger.Parameters, "name"), ", "))
}

func printRuleSummary(rule *whisk.Rule) {
    fmt.Fprintf(color.Output, "%s %s\n", boldString(fmt.Sprintf("%4s", "rule")),
        getFullName(rule.Namespace, "", rule.Name))
    fmt.Fprintf(color.Output, "   (%s: %s)\n", boldString(wski18n.T("status")), rule.Status)
}

func printEntitySummary(entityType string, fullName string, description string, params string) {
    emptyParams := "none defined"
    if len(params) <= 0 {
        params = emptyParams
    }
    if len(description) > 0 {
        fmt.Fprintf(color.Output, "%s %s: %s\n", boldString(entityType), fullName, description)
    } else if params != emptyParams {
        descriptionFromParams := buildParamDescription(params)
        fmt.Fprintf(color.Output, "%s %s: %s\n", boldString(entityType), fullName, descriptionFromParams)
    } else {
        fmt.Fprintf(color.Output, "%s %s\n", boldString(entityType), fullName)
    }
    fmt.Fprintf(color.Output, "   (%s: %s)\n", boldString(wski18n.T("parameters")), params)
}

//  getParamUnion(keyValArrAnnots, keyValArrParams, key) returns the union
//      of parameters listed under annotations (keyValArrAnnots, using key) and
//      bound parameters (keyValArrParams). Bound parameters will be denoted with
//      a prefixed "*", and finalized bound parameters (can't be changed by
//      user) will be denoted by a prefixed "**".
func getParamUnion(keyValArrAnnots whisk.KeyValueArr, keyValArrParams whisk.KeyValueArr, key string) []string {
    var res []string
    tag := "*"
    if getValueBool(keyValArrAnnots, "final") {
        tag = "**"
    }
    boundParams := getKeys(keyValArrParams)
    annotatedParams := getChildValueStrings(keyValArrAnnots, "parameters", key)
    res = append(boundParams, annotatedParams...)       // Create union of boundParams and annotatedParams with duplication
    for i := 0; i < len(res); i++ {
        for j := i + 1; j < len(res); j++ {
            if res[i] == res[j] {
                res = append(res[:j], res[j+1:]...)     // Remove duplicate entry
            }
        }
    }
    sort.Strings(res)
    res = tagBoundParams(boundParams, res, tag)
    return res
}

//  tagBoundParams(boundParams, paramUnion, tag) returns the list paramUnion with
//      all strings listed under boundParams set with a prefix tag.
func tagBoundParams(boundParams []string, paramUnion []string, tag string) []string {
    res := paramUnion
    for i := 0; i < len(boundParams); i++ {
        for j := 0; j < len(res); j++ {
            if boundParams[i] == res[j] {
                res[j] = fmt.Sprintf("%s%s", tag, res[j])
            }
        }
    }
    return res
}

//  buildParamDescription(params) returns a default entity description for
//      `$ wsk [ENTITY] get [ENTITY_NAME] --summary` when parameters are defined,
//      but the entity description under annotations is not.
func buildParamDescription(params string) string {
    preamble := "Returns a result based on parameter"
    params = strings.Replace(params, "*", "", -1)
    temp := strings.Split(params, ",")
    if len(temp) > 1 {
        lastParam := temp[len(temp) - 1]
        newParams := strings.Replace(params, fmt.Sprintf(",%s", lastParam), fmt.Sprintf(" and%s", lastParam), 1)
        return fmt.Sprintf("%ss %s", preamble, newParams)
    }
    return fmt.Sprintf("%s %s", preamble, params)
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

func deleteKey(key string, keyValueArr whisk.KeyValueArr) (whisk.KeyValueArr) {
    for i := 0; i < len(keyValueArr); i++ {
        if keyValueArr[i].Key == key {
            keyValueArr = append(keyValueArr[:i], keyValueArr[i + 1:]...)
            break
        }
    }

    return keyValueArr
}

func addKeyValue(key string, value interface{}, keyValueArr whisk.KeyValueArr) (whisk.KeyValueArr) {
    keyValue := whisk.KeyValue{
        Key:  key,
        Value: value,
    }

    return append(keyValueArr, keyValue)
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

func getValueString(keyValueArr whisk.KeyValueArr, key string) (string) {
    var value interface{}
    var res string

    value = keyValueArr.GetValue(key)
    castedValue, canCast := value.(string)

    if (canCast) {
        res = castedValue
    }

    whisk.Debug(whisk.DbgInfo, "Got string value '%v' for key '%s'\n", res, key)

    return res
}

func getValueBool(keyValueArr whisk.KeyValueArr, key string) (bool) {
    var value interface{}
    var res bool

    value = keyValueArr.GetValue(key)
    castedValue, canCast := value.(bool)

    if (canCast) {
        res = castedValue
    }

    whisk.Debug(whisk.DbgInfo, "Got bool value '%v' for key '%s'\n", res, key)

    return res
}

func getChildValues(keyValueArr whisk.KeyValueArr, key string, childKey string) ([]interface{}) {
    var value interface{}
    var res []interface{}

    value = keyValueArr.GetValue(key)

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

func getValueFromJSONResponse(field string, response map[string]interface {}) (interface{}) {
    var res interface{}

    for key, value := range response {
        if key == field {
            res = value
            break
        }
    }

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

func printJsonNoColor(decoded interface{}, stream ...io.Writer) {
    var output bytes.Buffer

    buffer := new(bytes.Buffer)
    encoder := json.NewEncoder(buffer)
    encoder.SetEscapeHTML(false)
    encoder.Encode(&decoded)
    json.Indent(&output, buffer.Bytes(), "", "    ")

    if len(stream) > 0 {
        fmt.Fprintf(stream[0], "%s", string(output.Bytes()))
    } else {
        fmt.Fprintf(os.Stdout, "%s", string(output.Bytes()))
    }
}

func unpackGzip(inpath string, outpath string) error {
    var exists bool
    var err error

    exists, err = FileExists(outpath)

    if err != nil {
        return err
    }

    if exists {
        errStr := wski18n.T("The file '{{.name}}' already exists.  Delete it and retry.",
            map[string]interface{}{"name": outpath})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    exists, err = FileExists(inpath)

    if err != nil {
        return err
    }

    if !exists {
        errMsg := wski18n.T("File '{{.name}}' is not a valid file or it does not exist",
            map[string]interface{}{
                "name": inpath,
            })
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_USAGE,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return whiskErr
    }

    unGzFile, err := os.Create(outpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", outpath, err)
        errStr := wski18n.T("Error creating unGzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": outpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer unGzFile.Close()

    gzFile, err := os.Open(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Open(%s) failed: %s\n", inpath, err)
        errStr := wski18n.T("Error opening Gzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer gzFile.Close()

    gzReader, err := gzip.NewReader(gzFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "gzip.NewReader() failed: %s\n", err)
        errStr := wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    _, err = io.Copy(unGzFile, gzReader)
    if err != nil {
        whisk.Debug(whisk.DbgError, "io.Copy() failed: %s\n", err)
        errStr := wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": inpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    return nil
}

func unpackZip(inpath string) error {
    exists, err := FileExists(inpath)

    if err != nil {
        return err
    }

    if !exists {
        errMsg := wski18n.T("File '{{.name}}' is not a valid file or it does not exist",
            map[string]interface{}{
                "name": inpath,
            })
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_USAGE,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return whiskErr
    }
    zipFileReader, err := zip.OpenReader(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "zip.OpenReader(%s) failed: %s\n", inpath, err)
        errStr := wski18n.T("Unable to opens '{{.name}}' for unzipping: {{.err}}",
            map[string]interface{}{"name": inpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
                errStr := wski18n.T("Unable to create directory '{{.dir}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"dir": item.Name, "name": inpath, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        if itemType.IsRegular() {
            unzipFile, err := item.Open()
            defer unzipFile.Close()
            if err != nil {
                whisk.Debug(whisk.DbgError, "'%s' Open() failed: %s\n", item.Name, err)
                errStr := wski18n.T("Unable to open zipped file '{{.file}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            targetFile, err := os.Create(itemName)
            if err != nil {
                whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", itemName, err)
                errStr := wski18n.T("Unable to create file '{{.file}}' while unzipping '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            if _, err := io.Copy(targetFile, unzipFile); err != nil {
                whisk.Debug(whisk.DbgError, "io.Copy() of '%s' failed: %s\n", itemName, err)
                errStr := wski18n.T("Unable to unzip file '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": itemName, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }
    }

    return nil
}

func unpackTar(inpath string) error {
    exists, err := FileExists(inpath)

    if err != nil {
        return err
    }

    if !exists {
        errMsg := wski18n.T("File '{{.name}}' is not a valid file or it does not exist",
            map[string]interface{}{
                "name": inpath,
            })
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_USAGE,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return whiskErr
    }

    tarFileReader, err := os.Open(inpath)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Open(%s) failed: %s\n", inpath, err)
        errStr := wski18n.T("Error opening tar file '{{.name}}': {{.err}}",
            map[string]interface{}{"name": inpath, "err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
            errStr := wski18n.T("Error reading tar file '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": inpath, "err": err})
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "tar file item - %#v\n", item)
        switch item.Typeflag {
        case tar.TypeDir:
            if err := os.MkdirAll(item.Name, os.FileMode(item.Mode)); err != nil {
                whisk.Debug(whisk.DbgError, "os.MkdirAll(%s, %d) failed: %s\n", item.Name, item.Mode, err)
                errStr := wski18n.T("Unable to create directory '{{.dir}}' while untarring '{{.name}}': {{.err}}",
                        map[string]interface{}{"dir": item.Name, "name": inpath, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        case tar.TypeReg:
            untarFile, err:= os.OpenFile(item.Name, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, os.FileMode(item.Mode))
            defer untarFile.Close()
            if err != nil {
                whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", item.Name, err)
                errStr := wski18n.T("Unable to create file '{{.file}}' while untarring '{{.name}}': {{.err}}",
                        map[string]interface{}{"file": item.Name, "name": inpath, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            if _, err := io.Copy(untarFile, tReader); err != nil {
                whisk.Debug(whisk.DbgError, "io.Copy() of '%s' failed: %s\n", item.Name, err)
                errStr := wski18n.T("Unable to untar file '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": item.Name, "err": err})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        default:
            whisk.Debug(whisk.DbgError, "Unexpected tar file type of %q\n", item.Typeflag)
            errStr := wski18n.T("Unable to untar '{{.name}}' due to unexpected tar file type\n",
                    map[string]interface{}{"name": item.Name})
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}

func CheckArgs(args []string, minimumArgNumber int, maximumArgNumber int, commandName string,
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
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    } else if len(args) > maximumArgNumber {
        whisk.Debug(whisk.DbgError, fmt.Sprintf("%s command must have %s %d argument(s)\n", commandName,
            exactlyOrNoMoreThan, maximumArgNumber))
        errMsg := wski18n.T("Invalid argument(s): {{.args}}. {{.required}}",
            map[string]interface{}{"args": strings.Join(args[maximumArgNumber:], ", "), "required": requiredArgMsg})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return whiskErr
    } else {
        return nil
    }
}

func normalizeNamespace(namespace string) (string) {
    if (namespace == "_") {
        namespace = wski18n.T("default")
    }

    return namespace
}

func getClientNamespace() (string) {
    return normalizeNamespace(Client.Config.Namespace)
}

func readFile(filename string) (string, error) {
    exists, err := FileExists(filename)

    if err != nil {
        return "", err
    }

    if !exists {
        errMsg := wski18n.T("File '{{.name}}' is not a valid file or it does not exist",
            map[string]interface{}{
                "name": filename,
            })
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_USAGE,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)

        return "", whiskErr
    }

    file, err := ioutil.ReadFile(filename)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.ioutil.ReadFile(%s) error: %s\n", filename, err)
        errMsg := wski18n.T("Unable to read the file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": filename, "err": err})
        whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXIT_CODE_ERR_GENERAL,
            whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
        return "", whiskErr
    }

    return string(file), nil
}

func writeFile(filename string, content string) (error) {
    file, err := os.Create(filename)

    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) error: %#v\n", filename, err)
        errMsg := wski18n.T("Cannot create file '{{.name}}': {{.err}}",
            map[string]interface{}{"name": filename, "err": err})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_USAGE, whisk.DISPLAY_MSG,
            whisk.DISPLAY_USAGE)
        return whiskErr
    }

    defer file.Close()

    if _, err = file.WriteString(content); err != nil {
        whisk.Debug(whisk.DbgError, "File.WriteString(%s) error: %#v\n", content, err)
        errMsg := wski18n.T("Cannot create file '{{.name}}': {{.err}}",
            map[string]interface{}{"name": filename, "err": err})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_USAGE, whisk.DISPLAY_MSG,
            whisk.DISPLAY_USAGE)
        return whiskErr
    }

    return nil
}

func FileExists(file string) (bool, error) {
    _, err := os.Stat(file)

    if err != nil {
        if os.IsNotExist(err) == true {
            return false, nil
        } else {
            whisk.Debug(whisk.DbgError, "os.Stat(%s) error: %#v\n", file, err)
            errMsg := wski18n.T("Cannot access file '{{.name}}': {{.err}}",
                map[string]interface{}{"name": file, "err": err})
            whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_USAGE,
                whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return true, whiskErr
        }
    }

    return true, nil
}

func fieldExists(value interface{}, field string) (bool) {
    element := reflect.ValueOf(value).Elem()

    for i := 0; i < element.NumField(); i++ {
        if strings.ToLower(element.Type().Field(i).Name) == strings.ToLower(field) {
            return true
        }
    }

    return false
}

func printField(value interface{}, field string) {
    var matchFunc = func(structField string) bool {
        return strings.ToLower(structField) == strings.ToLower(field)
    }

    structValue := reflect.ValueOf(value)
    fieldValue := reflect.Indirect(structValue).FieldByNameFunc(matchFunc)

    printJSON(fieldValue.Interface())
}

func parseShared(shared string) (bool, bool, error) {
    var isShared, isSet bool

    if strings.ToLower(shared) == "yes" {
        isShared = true
        isSet = true
    } else if strings.ToLower(shared) == "no" {
        isShared = false
        isSet = true
    } else if len(shared) == 0 {
        isSet = false
    } else {
        whisk.Debug(whisk.DbgError, "Cannot use value '%s' for shared.\n", shared)
        errMsg := wski18n.T("Cannot use value '{{.arg}}' for shared.", map[string]interface{}{"arg": shared})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG,
            whisk.DISPLAY_USAGE)
        return false, false, whiskErr
    }

    whisk.Debug(whisk.DbgError, "Sharing is '%t'\n", isShared)

    return isShared, isSet, nil
}

func max(a int, b int) int {
    if (a > b) {
        return a
    }
    return b
}

func min (a int, b int) int {
    if (a < b) {
        return a
    }
    return b
}

func ReadProps(path string) (map[string]string, error) {

    props := map[string]string{}

    file, err := os.Open(path)
    if err != nil {
        // If file does not exist, just return props
        whisk.Debug(whisk.DbgWarn, "Unable to read whisk properties file '%s' (file open error: %s); falling back to default properties\n" ,path, err)
        return props, nil
    }
    defer file.Close()

    lines := []string{}
    scanner := bufio.NewScanner(file)
    for scanner.Scan() {
        lines = append(lines, scanner.Text())
    }

    props = map[string]string{}
    for _, line := range lines {
        re := regexp.MustCompile("#.*")
        line = re.ReplaceAllString(line, "")
        line = strings.TrimSpace(line)
        kv := strings.Split(line, "=")
        if len(kv) != 2 {
            // Invalid format; skip
            continue
        }
        props[kv[0]] = kv[1]
    }

    return props, nil

}

func WriteProps(path string, props map[string]string) error {

    file, err := os.Create(path)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", path, err)
        errStr := wski18n.T("Whisk properties file write failure: {{.err}}", map[string]interface{}{"err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer file.Close()

    writer := bufio.NewWriter(file)
    defer writer.Flush()
    for key, value := range props {
        line := fmt.Sprintf("%s=%s", strings.ToUpper(key), value)
        _, err = fmt.Fprintln(writer, line)
        if err != nil {
            whisk.Debug(whisk.DbgError, "fmt.Fprintln() write to '%s' failed: %s\n", path, err)
            errStr := wski18n.T("Whisk properties file write failure: {{.err}}", map[string]interface{}{"err": err})
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}

func getSpaceGuid() (string, error) {
    // get current props
    props, err := ReadProps(Properties.PropsFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
        errStr := wski18n.T("Unable to obtain the `auth` property value: {{.err}}", map[string]interface{}{"err": err})
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return "", werr
    }

    // get the auth key and parse out the space guid
    if authToken, hasProp := props["AUTH"]; hasProp {
        spaceGuid := strings.Split(authToken, ":")[0]
        return spaceGuid, nil
    }

    whisk.Debug(whisk.DbgError, "auth not found in properties: %#q\n", props)
    errStr := wski18n.T("Auth key property value is not set")
    werr := whisk.MakeWskError(errors.New(errStr), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
    return "", werr
}

func isBlockingTimeout(err error) (bool) {
    var blockingTimeout bool

    whiskErr, isWhiskErr := err.(*whisk.WskError)

    if isWhiskErr && whiskErr.TimedOut {
        blockingTimeout = true
    }

    return blockingTimeout
}

func isApplicationError(err error) (bool) {
    var applicationError bool

    whiskErr, isWhiskErr := err.(*whisk.WskError)

    if isWhiskErr && whiskErr.ApplicationError {
        applicationError = true
    }

    return applicationError
}
