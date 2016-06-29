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
    "net/http"

    "../../go-whisk/whisk"

    //"github.com/fatih/color"
    "github.com/spf13/cobra"
)

var packageCmd = &cobra.Command{
    Use:   "package",
    Short: "work with packages",
}

/*
bind parameters to the package

Usage:
  wsk package bind <package string> <name string> [flags]

Flags:
  -a, --annotation value   annotations (default [])
  -p, --param value        default parameters (default [])

Global Flags:
      --apihost string      whisk API host
      --apiversion string   whisk API version
  -u, --auth string         authorization key
  -d, --debug               debug level output
  -v, --verbose             verbose output

Request URL
PUT https://openwhisk.ng.bluemix.net/api/v1/namespaces/<namespace>/packages/<bindingname>

payload:
{
  "binding": {
    "namespace": "<pkgnamespace>",
    "name": "<pkgname>"
  },
  "annotations": [
    {"value": "abv1", "key": "ab1"}
  ],
  "parameters": [
    {"value": "pbv1", "key": "pb1"}
  ],
  "publish": false
}

*/
var packageBindCmd = &cobra.Command{
    Use:   "bind <package string> <name string>",
    Short: "bind parameters to a package",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 2 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; either the package name or the binding name is missing", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        packageName := args[0]
        pkgQName, err := parseQualifiedName(packageName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", packageName, err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", packageName)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        bindingName := args[1]
        bindQName, err := parseQualifiedName(bindingName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", bindingName, err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", bindingName)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = bindQName.namespace


        // Convert the binding's list of default parameters from a string into []KeyValue
        // The 1 or more --param arguments have all been combined into a single []string
        // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]

        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := parseParametersArray(flags.common.param)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseParametersArray(%#v) failed: %s\n", flags.common.param, err)
            errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        // Convert the binding's list of default annotations from a string into []KeyValue
        // The 1 or more --annotation arguments have all been combined into a single []string
        // e.g.   --a arg1,arg2 --a arg3,arg4   ->  [arg1, arg2, arg3, arg4]
        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := parseAnnotations(flags.common.annotation)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAnnotations(%#v) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf("Invalid annotation argument '%#v': %s", flags.common.annotation, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        binding := whisk.Binding{
            Name:      pkgQName.entityName,
            Namespace: pkgQName.namespace,
        }

        p := &whisk.BindingPackage{
            Name:        bindQName.entityName,
            Annotations: annotations,
            Parameters:  parameters,
            Binding:     binding,
        }

        _,  _, err = client.Packages.Insert(p, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Insert(%#v, false) failed: %s\n", p, err)
            errStr := fmt.Sprintf("Binding creation failed: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("ok: created binding %s\n", bindingName)
        return nil
    },
}

var packageCreateCmd = &cobra.Command{
    Use:   "create <name string>",
    Short: "create a new package",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var shared, sharedSet bool

        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 argument); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; the package name is the only expected argument", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        if (flags.common.shared == "yes") {
            shared = true
            sharedSet = true
        } else if (flags.common.shared == "no") {
            shared = false
            sharedSet = true
        } else {
            sharedSet = false
        }

        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := parseParametersArray(flags.common.param)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseParametersArray(%#v) failed: %s\n", flags.common.param, err)
            errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := parseAnnotations(flags.common.annotation)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAnnotations(%#v) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf("Invalid annotation argument '%#v': %s", flags.common.annotation, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        var p whisk.PackageInterface
        if sharedSet {
            p = &whisk.SentPackagePublish{
                Name:        qName.entityName,
                Namespace:   qName.namespace,
                Publish:     shared,
                Annotations: annotations,
                Parameters:  parameters,
            }
        } else {
            p = &whisk.SentPackageNoPublish{
                Name:        qName.entityName,
                Namespace:   qName.namespace,
                Publish:     shared,
                Annotations: annotations,
                Parameters:  parameters,
            }
        }

        p, _, err = client.Packages.Insert(p, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Insert(%#v, false) failed: %s\n", p, err)
            errStr := fmt.Sprintf("Package creation failed: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        //fmt.Printf("%s created package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
        fmt.Printf("ok: created package %s\n", qName.entityName)
        return nil
    },
}

/*
usage: wsk package update [-h] [-u AUTH] [-a ANNOTATION ANNOTATION]
                          [-p PARAM PARAM] [--shared [{yes,no}]]
                          name

positional arguments:
  name                  the name of the package

optional arguments:
  -h, --help            show this help message and exit
  -u AUTH, --auth AUTH  authorization key
  -a ANNOTATION ANNOTATION, --annotation ANNOTATION ANNOTATION
                        annotations
  -p PARAM PARAM, --param PARAM PARAM
                        default parameters
  --shared [{yes,no}]   shared action (default: private)

  UPDATE:
        If --shared is present, published is true. Otherwise, published is false.

PUT https://172.17.0.1/api/v1/namespaces/_/packages/slack?overwrite=true: 400  []
    https://172.17.0.1/api/v1/namespaces/_/packages/slack?overwrite=true

Request URL

https://raw.githubusercontent.com/api/v1/namespaces/_/packages/slack?overwrite=true

payload:
        {"name":"slack","publish":true,"annotations":[],"parameters":[],"binding":false}


 */
var packageUpdateCmd = &cobra.Command{
    Use:   "update <name string>",
    Short: "update an existing package",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var shared, sharedSet bool

        if len(args) < 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 argument); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; the package name is the only expected argument", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        if (flags.common.shared == "yes") {
            shared = true
            sharedSet = true
        } else if (flags.common.shared == "no") {
            shared = false
            sharedSet = true
        } else {
            sharedSet = false
        }

        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := parseParametersArray(flags.common.param)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseParametersArray(%#v) failed: %s\n", flags.common.param, err)
            errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := parseAnnotations(flags.common.annotation)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAnnotations(%#v) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf("Invalid annotation argument '%#v': %s", flags.common.annotation, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        var p whisk.PackageInterface
        if sharedSet {
            p = &whisk.SentPackagePublish{
                Name:        qName.entityName,
                Namespace:   qName.namespace,
                Publish:     shared,
                Annotations: annotations,
                Parameters:  parameters,
            }
        } else {
            p = &whisk.SentPackageNoPublish{
                Name:        qName.entityName,
                Namespace:   qName.namespace,
                Publish:     shared,
                Annotations: annotations,
                Parameters:  parameters,
            }
        }

        p, _, err = client.Packages.Insert(p, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Insert(%#v, true) failed: %s\n", p, err)
            errStr := fmt.Sprintf("Package update failed: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        //fmt.Printf("%s updated package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
        fmt.Printf("ok: updated package %s\n",qName.entityName)
        return nil
    },
}

var packageGetCmd = &cobra.Command{
    Use:   "get <name string>",
    Short: "get package",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 argument); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; the package name is the only expected argument", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        xPackage, _, err := client.Packages.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Get(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf("Package update failed: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        if flags.common.summary {
            printSummary(xPackage)
        } else {
            //fmt.Printf("%s got package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
            //printJSON(xPackage)
            fmt.Printf("ok: got package %s\n", qName.entityName)
            printJsonNoColor(xPackage)
        }

        return nil
    },
}

var packageDeleteCmd = &cobra.Command{
    Use:   "delete <name string>",
    Short: "delete package",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 argument); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; the package name is the only expected argument", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        _, err = client.Packages.Delete(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Delete(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf("Package delete failed: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        //fmt.Printf("%s deleted package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
        fmt.Printf("ok: deleted package %s\n", qName.entityName)
        return nil
    },
}

var packageListCmd = &cobra.Command{
    Use:   "list <namespace string>",
    Short: "list all packages",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var shared bool

        qName := qualifiedName{}
        if len(args) == 1 {
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
                werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "An empty namespace in the package name '%s' is invalid \n", args[0])
                errStr := fmt.Sprintf("No valid namespace detected.  Make sure that namespace argument is preceded by a \"/\"")
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }

            client.Namespace = ns
        }

        if (flags.common.shared == "yes") {
            shared = true
        } else  {
            shared = false
        }

        options := &whisk.PackageListOptions{
            Skip:   flags.common.skip,
            Limit:  flags.common.limit,
            Public: shared,
            Docs:   flags.common.full,
        }

        packages, _, err := client.Packages.List(options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.List(%+v) failed: %s\n", options, err)
            errStr := fmt.Sprintf("Unable to obtain package list: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        printList(packages)
        return nil
    },
}

var packageRefreshCmd = &cobra.Command{
    Use:   "refresh <namespace string>",
    Short: "refresh package bindings",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 argument); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; the package name is the only expected argument", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s\n", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        currentNamespace := client.Config.Namespace
        client.Config.Namespace = qName.namespace

        defer func() {
            client.Config.Namespace = currentNamespace
        }()

        updates, resp, err := client.Packages.Refresh()
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Packages.Refresh() of namespace '%s' failed: %s\n", client.Config.Namespace, err)
            errStr := fmt.Sprintf("Package refresh for namespace '%s' failed: %s", client.Config.Namespace, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        whisk.Debug(whisk.DbgInfo, "Refresh updates received: %#v\n", updates)

        switch resp.StatusCode {
        case http.StatusOK:
            fmt.Printf("%s refreshed successfully\n", client.Config.Namespace)

            fmt.Println("created bindings:")

            if len(updates.Added) > 0 {
                //printJSON(updates.Added)
                printArrayContents(updates.Added)
            }

            fmt.Println("updated bindings:")

            if len(updates.Updated) > 0 {
                //printJSON(updates.Updated)
                printArrayContents(updates.Updated)
            }

            fmt.Println("deleted bindings:")

            if len(updates.Deleted) > 0 {
                //printJSON(updates.Deleted)
                printArrayContents(updates.Deleted)
            }

        case http.StatusNotImplemented:
            whisk.Debug(whisk.DbgError, "client.Packages.Refresh() for namespace '%s' returned 'Not Implemented' HTTP status code: %d\n", client.Config.Namespace, resp.StatusCode)
            errStr := fmt.Sprintf("The package refresh feature is not implement in the target deployment")
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        default:
            whisk.Debug(whisk.DbgError, "client.Packages.Refresh() for namespace '%s' returned an unexpected HTTP status code: %d\n",  client.Config.Namespace, resp.StatusCode)
            errStr := fmt.Sprintf("Package refresh for namespace '%s' failed due to unexpected HTTP status code: %d",  client.Config.Namespace, resp.StatusCode)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        return nil
    },
}

func init() {

    packageCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    packageCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")
    packageCreateCmd.Flags().StringVarP(&flags.xPackage.serviceGUID, "service_guid", "s", "", "a unique identifier of the service")
    packageCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "" , "shared action (default: private)")

    packageUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    packageUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")
    packageUpdateCmd.Flags().StringVarP(&flags.xPackage.serviceGUID, "service_guid", "s", "", "a unique identifier of the service")
    packageUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "shared action (default: private)")

    packageGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, "summarize entity details")

    packageBindCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    packageBindCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")

    packageListCmd.Flags().StringVar(&flags.common.shared, "shared", "", "include publicly shared entities in the result")
    packageListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "skip this many entities from the head of the collection")
    packageListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return this many entities from the collection")
    packageListCmd.Flags().BoolVar(&flags.common.full, "full", false, "include full entity description")

    packageCmd.AddCommand(
        packageBindCmd,
        packageCreateCmd,
        packageUpdateCmd,
        packageGetCmd,
        packageDeleteCmd,
        packageListCmd,
        packageRefreshCmd,
    )
}
