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
  "strings"

  "../../go-whisk/whisk"

  "github.com/fatih/color"
  "github.com/spf13/cobra"
)

var packageCmd = &cobra.Command{
  Use:   "package",
  Short: "work with packages",
}

var packageBindCmd = &cobra.Command{
  Use:           "bind PACKAGE_NAME BOUND_PACKAGE_NAME",
  Short:         "bind parameters to a package",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error

    if len(args) < 2 {
      whisk.Debug(whisk.DbgError, "Package bind command must have at least one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s). A package name and binding name are required.")
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    } else if len(args) > 2 {
      whisk.Debug(whisk.DbgError, "Package bind command must not have more than two arguments\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[2:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
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
    parameters, err := getJSONFromArguments(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    // Convert the binding's list of default annotations from a string into []KeyValue
    // The 1 or more --annotation arguments have all been combined into a single []string
    // e.g.   --a arg1,arg2 --a arg3,arg4   ->  [arg1, arg2, arg3, arg4]
    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromArguments(flags.common.annotation, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
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

    _, _, err = client.Packages.Insert(p, false)
    if err != nil {
      whisk.Debug(whisk.DbgError, "client.Packages.Insert(%#v, false) failed: %s\n", p, err)
      errStr := fmt.Sprintf("Binding creation failed: %s", err)
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    fmt.Fprintf(color.Output, "%s created binding %s\n", color.GreenString("ok:"), boldString(bindingName))
    return nil
  },
}

var packageCreateCmd = &cobra.Command{
  Use:           "create PACKAGE_NAME",
  Short:         "create a new package",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var shared, sharedSet bool

    if len(args) < 1 {
      whisk.Debug(whisk.DbgError, "Package create command must have at least one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s). A package name is required.")
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    } else if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package create command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
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

    if flags.common.shared == "yes" {
      shared = true
      sharedSet = true
    } else if flags.common.shared == "no" {
      shared = false
      sharedSet = true
    } else {
      sharedSet = false
    }

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromArguments(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromArguments(flags.common.annotation, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
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

    fmt.Fprintf(color.Output, "%s created package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
    return nil
  },
}

var packageUpdateCmd = &cobra.Command{
  Use:           "update PACKAGE_NAME",
  Short:         "update an existing package",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var shared, sharedSet bool

    if len(args) < 1 {
      whisk.Debug(whisk.DbgError, "Package update command must have at least one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s). A package name is required.")
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    } else if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package update command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
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

    if flags.common.shared == "yes" {
      shared = true
      sharedSet = true
    } else if flags.common.shared == "no" {
      shared = false
      sharedSet = true
    } else {
      sharedSet = false
    }

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromArguments(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromArguments(flags.common.annotation, true)
    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
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

    fmt.Fprintf(color.Output, "%s updated package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
    return nil
  },
}

var packageGetCmd = &cobra.Command{
  Use:           "get PACKAGE_NAME",
  Short:         "get package",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error

    if len(args) < 1 {
      whisk.Debug(whisk.DbgError, "Package get command must have at least one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s). A package name is required.")
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    } else if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package update command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
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
      fmt.Fprintf(color.Output, "%s got package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
      printJsonNoColor(xPackage)
    }

    return nil
  },
}

var packageDeleteCmd = &cobra.Command{
  Use:           "delete PACKAGE_NAME",
  Short:         "delete package",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error

    if len(args) < 1 {
      whisk.Debug(whisk.DbgError, "Package delete command must have at least one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s). A package name is required.")
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    } else if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package delete command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
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

    fmt.Fprintf(color.Output, "%s deleted package %s\n", color.GreenString("ok:"), boldString(qName.entityName))
    return nil
  },
}

var packageListCmd = &cobra.Command{
  Use:           "list [NAMESPACE]",
  Short:         "list all packages",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
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
    } else if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package list command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
        whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return whiskErr
    }

    if flags.common.shared == "yes" {
      shared = true
    } else {
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
  Use:           "refresh [NAMESPACE]",
  Short:         "refresh package bindings",
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       setupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var qName qualifiedName

    if len(args) > 1 {
      whisk.Debug(whisk.DbgError, "Package refresh command must not have more than one argument\n")
      errMsg := fmt.Sprintf("Invalid argument(s): %s.", strings.Join(args[1:], ", "))
      whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG,
        whisk.DISPLAY_USAGE)
      return whiskErr
    } else {
      if len(args) == 0 {
        qName, err = parseQualifiedName("")
      } else {
        qName, err = parseQualifiedName(args[0])
      }

      if err != nil {
        whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
        errMsg := fmt.Sprintf("Failed to parse qualified name: %s\n", args[0])
        werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
          whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
      }
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
        printArrayContents(updates.Added)
      }

      fmt.Println("updated bindings:")

      if len(updates.Updated) > 0 {
        printArrayContents(updates.Updated)
      }

      fmt.Println("deleted bindings:")

      if len(updates.Deleted) > 0 {
        printArrayContents(updates.Deleted)
      }

    case http.StatusNotImplemented:
      whisk.Debug(whisk.DbgError, "client.Packages.Refresh() for namespace '%s' returned 'Not Implemented' HTTP status code: %d\n", client.Config.Namespace, resp.StatusCode)
      errStr := fmt.Sprintf("The package refresh feature is not implement in the target deployment")
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    default:
      whisk.Debug(whisk.DbgError, "client.Packages.Refresh() for namespace '%s' returned an unexpected HTTP status code: %d\n", client.Config.Namespace, resp.StatusCode)
      errStr := fmt.Sprintf("Package refresh for namespace '%s' failed due to unexpected HTTP status code: %d", client.Config.Namespace, resp.StatusCode)
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    return nil
  },
}

func init() {

  packageCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotation values in `KEY VALUE` format")
  packageCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameter values in `KEY VALUE` format")
  packageCreateCmd.Flags().StringVarP(&flags.xPackage.serviceGUID, "service_guid", "s", "", "a unique identifier of the service")
  packageCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "package visibility `SCOPE`; yes = shared, no = private")

  packageUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotation values in `KEY VALUE` format")
  packageUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameter values in `KEY VALUE` format")
  packageUpdateCmd.Flags().StringVarP(&flags.xPackage.serviceGUID, "service_guid", "s", "", "a unique identifier of the service")
  packageUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "package visibility `SCOPE`; yes = shared, no = private")

  packageGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, "summarize entity details")

  packageBindCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotation values in `KEY VALUE` format")
  packageBindCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameter values in `KEY VALUE` format")

  packageListCmd.Flags().StringVar(&flags.common.shared, "shared", "", "include publicly shared entities in the result")
  packageListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "exclude the first `SKIP` number of packages from the result")
  packageListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return `LIMIT` number of packages from the collection")
  packageListCmd.Flags().BoolVar(&flags.common.full, "full", false, "include full package description")

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
