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
  "errors"
  "fmt"
  "net/http"

  "../../go-whisk/whisk"
  "../wski18n"

  "github.com/fatih/color"
  "github.com/spf13/cobra"
)

var packageCmd = &cobra.Command{
  Use:   "package",
  Short: wski18n.T("work with packages"),
}

var packageBindCmd = &cobra.Command{
  Use:           "bind PACKAGE_NAME BOUND_PACKAGE_NAME",
  Short:         wski18n.T("bind parameters to a package"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var pkgQualifiedName = new(QualifiedName)
    var bindQualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 2, 2, "Package bind",
            wski18n.T("A package name and binding name are required.")); whiskErr != nil {
      return whiskErr
    }

    packageName := args[0]
    if pkgQualifiedName, err = NewQualifiedName(packageName); err != nil {
      return NewQualifiedNameError(packageName, err)
    }

    bindingName := args[1]
    if bindQualifiedName, err = NewQualifiedName(bindingName); err != nil {
      return NewQualifiedNameError(bindingName, err)
    }

    Client.Namespace = bindQualifiedName.GetNamespace()

    // Convert the binding's list of default parameters from a string into []KeyValue
    // The 1 or more --param arguments have all been combined into a single []string
    // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromStrings(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
          map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    // Convert the binding's list of default annotations from a string into []KeyValue
    // The 1 or more --annotation arguments have all been combined into a single []string
    // e.g.   --a arg1,arg2 --a arg3,arg4   ->  [arg1, arg2, arg3, arg4]
    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromStrings(flags.common.annotation, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.annotation, err)
      errStr := wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
          map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    binding := whisk.Binding{
      Name:      pkgQualifiedName.GetEntityName(),
      Namespace: pkgQualifiedName.GetNamespace(),
    }

    p := &whisk.BindingPackage{
      Name:        bindQualifiedName.GetEntityName(),
      Annotations: annotations.(whisk.KeyValueArr),
      Parameters:  parameters.(whisk.KeyValueArr),
      Binding:     binding,
    }

    _, _, err = Client.Packages.Insert(p, false)
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Insert(%#v, false) failed: %s\n", p, err)
      errStr := wski18n.T("Binding creation failed: {{.err}}", map[string]interface{}{"err":err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    fmt.Fprintf(color.Output, wski18n.T("{{.ok}} created binding {{.name}}\n",
      map[string]interface{}{"ok": color.GreenString(wski18n.T("ok:")), "name":boldString(bindingName)}))
    return nil
  },
}

var packageCreateCmd = &cobra.Command{
  Use:           "create PACKAGE_NAME",
  Short:         wski18n.T("create a new package"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var shared, sharedSet bool
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 1, 1, "Package create", wski18n.T("A package name is required.")); whiskErr != nil {
      return whiskErr
    }

    if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
      return NewQualifiedNameError(args[0], err)
    }

    Client.Namespace = qualifiedName.GetNamespace()

    if shared, sharedSet, err = parseShared(flags.common.shared); err != nil {
      whisk.Debug(whisk.DbgError, "parseShared(%s) failed: %s\n", flags.common.shared, err)
      return err
    }

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromStrings(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
          map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromStrings(flags.common.annotation, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.annotation, err)
      errStr := wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
          map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    p := &whisk.Package{
      Name:        qualifiedName.GetEntityName(),
      Namespace:   qualifiedName.GetNamespace(),
      Annotations: annotations.(whisk.KeyValueArr),
      Parameters:  parameters.(whisk.KeyValueArr),
    }

    if sharedSet {
      p.Publish = &shared
    }

    p, _, err = Client.Packages.Insert(p, false)
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Insert(%#v, false) failed: %s\n", p, err)
      errStr := wski18n.T(
        "Unable to create package '{{.name}}': {{.err}}",
        map[string]interface{}{
          "name": qualifiedName.GetEntityName(),
          "err": err,
        })
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    fmt.Fprintf(color.Output, wski18n.T("{{.ok}} created package {{.name}}\n",
      map[string]interface{}{"ok": color.GreenString(wski18n.T("ok:")), "name":boldString(qualifiedName.GetEntityName())}))
    return nil
  },
}

var packageUpdateCmd = &cobra.Command{
  Use:           "update PACKAGE_NAME",
  Short:         wski18n.T("update an existing package, or create a package if it does not exist"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var shared, sharedSet bool
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 1, 1, "Package update", wski18n.T("A package name is required.")); whiskErr != nil {
      return whiskErr
    }

    if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
      return NewQualifiedNameError(args[0], err)
    }

    Client.Namespace = qualifiedName.GetNamespace()

    if shared, sharedSet, err = parseShared(flags.common.shared); err != nil {
      whisk.Debug(whisk.DbgError, "parseShared(%s) failed: %s\n", flags.common.shared, err)
      return err
    }

    whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
    parameters, err := getJSONFromStrings(flags.common.param, true)

    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.param, err)
      errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
          map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
    annotations, err := getJSONFromStrings(flags.common.annotation, true)
    if err != nil {
      whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.annotation, err)
      errStr := wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
          map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
      return werr
    }

    p := &whisk.Package{
      Name:        qualifiedName.GetEntityName(),
      Namespace:   qualifiedName.GetNamespace(),
      Annotations: annotations.(whisk.KeyValueArr),
      Parameters:  parameters.(whisk.KeyValueArr),
    }

    if sharedSet {
      p.Publish = &shared
    }

    p, _, err = Client.Packages.Insert(p, true)
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Insert(%#v, true) failed: %s\n", p, err)
      errStr := wski18n.T("Package update failed: {{.err}}", map[string]interface{}{"err":err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    fmt.Fprintf(color.Output, wski18n.T("{{.ok}} updated package {{.name}}\n",
      map[string]interface{}{"ok": color.GreenString(wski18n.T("ok:")), "name":boldString(qualifiedName.GetEntityName())}))
    return nil
  },
}

var packageGetCmd = &cobra.Command{
  Use:           "get PACKAGE_NAME [FIELD_FILTER]",
  Short:         wski18n.T("get package"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var field string
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 1, 2, "Package get", wski18n.T("A package name is required.")); whiskErr != nil {
      return whiskErr
    }

    if len(args) > 1 {
      field = args[1]

      if !fieldExists(&whisk.Package{}, field) {
        errMsg := wski18n.T("Invalid field filter '{{.arg}}'.", map[string]interface{}{"arg": field})
        whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL,
          whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return whiskErr
      }
    }

    if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
      return NewQualifiedNameError(args[0], err)
    }
    Client.Namespace = qualifiedName.GetNamespace()

    xPackage, _, err := Client.Packages.Get(qualifiedName.GetEntityName())
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Get(%s) failed: %s\n", qualifiedName.GetEntityName(), err)
      errStr := wski18n.T(
        "Unable to get package '{{.name}}': {{.err}}",
        map[string]interface{}{
          "name": qualifiedName.GetEntityName(),
          "err":err,
        })
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    if flags.common.summary {
      printSummary(xPackage)
    } else {

      if len(field) > 0 {
        fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got package {{.name}}, displaying field {{.field}}\n",
          map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qualifiedName.GetEntityName()),
          "field": boldString(field)}))
        printField(xPackage, field)
      } else {
        fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got package {{.name}}\n",
          map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qualifiedName.GetEntityName())}))
        printJSON(xPackage)
      }
    }

    return nil
  },
}

var packageDeleteCmd = &cobra.Command{
  Use:           "delete PACKAGE_NAME",
  Short:         wski18n.T("delete package"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 1, 1, "Package delete", wski18n.T("A package name is required.")); whiskErr != nil {
      return whiskErr
    }

    if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
      return NewQualifiedNameError(args[0], err)
    }

    Client.Namespace = qualifiedName.GetNamespace()

    _, err = Client.Packages.Delete(qualifiedName.GetEntityName())
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Delete(%s) failed: %s\n", qualifiedName.GetEntityName(), err)
      errStr := wski18n.T(
        "Unable to delete package '{{.name}}': {{.err}}",
        map[string]interface{}{
          "name": qualifiedName.GetEntityName(),
          "err": err,
        })
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    fmt.Fprintf(color.Output,
      wski18n.T("{{.ok}} deleted package {{.name}}\n",
        map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qualifiedName.GetEntityName())}))
    return nil
  },
}

var packageListCmd = &cobra.Command{
  Use:           "list [NAMESPACE]",
  Short:         wski18n.T("list all packages"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 0, 1, "Package list",
      wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
      return whiskErr
    }

    if len(args) == 1 {
      if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
        return NewQualifiedNameError(args[0], err)
      }

      if len(qualifiedName.GetEntityName()) > 0 {
        return entityNameError(qualifiedName.GetEntityName())
      }

      Client.Namespace = qualifiedName.GetNamespace()
    }

    options := &whisk.PackageListOptions{
      Skip:   flags.common.skip,
      Limit:  flags.common.limit,
    }

    packages, _, err := Client.Packages.List(options)
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.List(%+v) failed: %s\n", options, err)
      errStr := wski18n.T("Unable to obtain the list of packages for namespace '{{.name}}': {{.err}}",
          map[string]interface{}{"name": getClientNamespace(), "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    sortByName := flags.common.nameSort
    printList(packages, sortByName)

    return nil
  },
}

var packageRefreshCmd = &cobra.Command{
  Use:           "refresh [NAMESPACE]",
  Short:         wski18n.T("refresh package bindings"),
  SilenceUsage:  true,
  SilenceErrors: true,
  PreRunE:       SetupClientConfig,
  RunE: func(cmd *cobra.Command, args []string) error {
    var err error
    var qualifiedName = new(QualifiedName)

    if whiskErr := CheckArgs(args, 0, 1, "Package refresh",
        wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
      return whiskErr
    }

    if len(args) == 0 {
      qualifiedName.namespace = getNamespaceFromProp()
    } else {
      if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
        return NewQualifiedNameError(args[0], err)
      }

      if len(qualifiedName.GetEntityName()) > 0 {
        return entityNameError(qualifiedName.GetEntityName())
      }
    }

    currentNamespace := Client.Config.Namespace
    Client.Config.Namespace = qualifiedName.GetNamespace()

    defer func() {
      Client.Config.Namespace = currentNamespace
    }()

    updates, resp, err := Client.Packages.Refresh()
    if err != nil {
      whisk.Debug(whisk.DbgError, "Client.Packages.Refresh() of namespace '%s' failed: %s\n", Client.Config.Namespace, err)
      errStr := wski18n.T("Package refresh for namespace '{{.name}}' failed: {{.err}}",
          map[string]interface{}{"name": Client.Config.Namespace, "err": err})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }
    whisk.Debug(whisk.DbgInfo, "Refresh updates received: %#v\n", updates)

    switch resp.StatusCode {
    case http.StatusOK:
      fmt.Printf(wski18n.T("'{{.name}}' refreshed successfully\n",
        map[string]interface{}{"name": Client.Config.Namespace}))

      fmt.Println(wski18n.T("created bindings:"))

      if len(updates.Added) > 0 {
        printArrayContents(updates.Added)
      }

      fmt.Println(wski18n.T("updated bindings:"))

      if len(updates.Updated) > 0 {
        printArrayContents(updates.Updated)
      }

      fmt.Println(wski18n.T("deleted bindings:"))

      if len(updates.Deleted) > 0 {
        printArrayContents(updates.Deleted)
      }

    case http.StatusNotImplemented:
      whisk.Debug(whisk.DbgError, "Client.Packages.Refresh() for namespace '%s' returned 'Not Implemented' HTTP status code: %d\n", Client.Config.Namespace, resp.StatusCode)
      errStr := wski18n.T("The package refresh feature is not implemented in the target deployment")
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    default:
      whisk.Debug(whisk.DbgError, "Client.Packages.Refresh() for namespace '%s' returned an unexpected HTTP status code: %d\n", Client.Config.Namespace, resp.StatusCode)
      errStr := wski18n.T("Package refresh for namespace '{{.name}}' failed due to unexpected HTTP status code: {{.code}}",
          map[string]interface{}{"name": Client.Config.Namespace, "code": resp.StatusCode})
      werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
      return werr
    }

    return nil
  },
}

func init() {
  packageCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
  packageCreateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
  packageCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
  packageCreateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
  packageCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "", wski18n.T("package visibility `SCOPE`; yes = shared, no = private"))

  packageUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
  packageUpdateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
  packageUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
  packageUpdateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
  packageUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", wski18n.T("package visibility `SCOPE`; yes = shared, no = private"))

  packageGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, wski18n.T("summarize package details; parameters with prefix \"*\" are bound"))

  packageBindCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
  packageBindCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
  packageBindCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
  packageBindCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))

  packageListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of packages from the result"))
  packageListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of packages from the collection"))
  packageListCmd.Flags().BoolVarP(&flags.common.nameSort, "name-sort", "n", false, wski18n.T("sorts a list alphabetically by entity name; only applicable within the limit/skip returned entity block"))

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
