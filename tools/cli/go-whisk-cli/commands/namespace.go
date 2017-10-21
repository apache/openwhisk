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
    "fmt"
    "errors"

    "github.com/spf13/cobra"
    "github.com/fatih/color"

    "../../go-whisk/whisk"
    "../wski18n"
)

// namespaceCmd represents the namespace command
var namespaceCmd = &cobra.Command{
    Use:   "namespace",
    Short: wski18n.T("work with namespaces"),
}

var namespaceListCmd = &cobra.Command{
    Use:   "list",
    Short: wski18n.T("list available namespaces"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        // add "TYPE" --> public / private

        if whiskErr := CheckArgs(args, 0, 0, "Namespace list", wski18n.T("No arguments are required.")); whiskErr != nil {
            return whiskErr
        }

        namespaces, _, err := Client.Namespaces.List()
        if err != nil {
            whisk.Debug(whisk.DbgError, "Client.Namespaces.List() error: %s\n", err)
            errStr := wski18n.T("Unable to obtain the list of available namespaces: {{.err}}",
                map[string]interface{}{"err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(namespaces, false) // `-n` flag applies to `namespace get`, not list, so must pass value false for printList here
        return nil
    },
}

var namespaceGetCmd = &cobra.Command{
    Use:   "get [NAMESPACE]",
    Short: wski18n.T("get triggers, actions, and rules in the registry for a namespace"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: SetupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var qualifiedName = new(QualifiedName)
        var err error

        if whiskErr := CheckArgs(args, 0, 1, "Namespace get",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        // Namespace argument is optional; defaults to configured property namespace
        if len(args) == 1 {
            if qualifiedName, err = NewQualifiedName(args[0]); err != nil {
                return NewQualifiedNameError(args[0], err)
            }

            if len(qualifiedName.GetEntityName()) > 0 {
                return entityNameError(qualifiedName.GetEntityName())
            }
        }

        namespace, _, err := Client.Namespaces.Get(qualifiedName.GetNamespace())

        if err != nil {
            whisk.Debug(whisk.DbgError, "Client.Namespaces.Get(%s) error: %s\n", getClientNamespace(), err)
            errStr := wski18n.T("Unable to obtain the list of entities for namespace '{{.namespace}}': {{.err}}",
                    map[string]interface{}{"namespace": getClientNamespace(), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_NETWORK,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output, wski18n.T("Entities in namespace: {{.namespace}}\n",
            map[string]interface{}{"namespace": boldString(getClientNamespace())}))
        sortByName := flags.common.nameSort
        printList(namespace.Contents.Packages, sortByName)
        printList(namespace.Contents.Actions, sortByName)
        printList(namespace.Contents.Triggers, sortByName)
        //No errors, lets attempt to retrieve the status of each rule #312
        for index, rule := range namespace.Contents.Rules {
            ruleStatus, _, err := Client.Rules.Get(rule.Name)
            if err != nil {
                errStr := wski18n.T("Unable to get status of rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": rule.Name, "err": err})
                fmt.Println(errStr)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            namespace.Contents.Rules[index].Status = ruleStatus.Status
        }
        printList(namespace.Contents.Rules, sortByName)

        return nil
    },
}

func init() {
    namespaceGetCmd.Flags().BoolVarP(&flags.common.nameSort, "name-sort", "n", false, wski18n.T("sorts a list alphabetically by entity name; only applicable within the limit/skip returned entity block"))

    namespaceCmd.AddCommand(
        namespaceListCmd,
        namespaceGetCmd,
    )
}
