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
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        // add "TYPE" --> public / private

        if whiskErr := checkArgs(args, 0, 0, "Namespace list", wski18n.T("No arguments are required.")); whiskErr != nil {
            return whiskErr
        }

        namespaces, _, err := client.Namespaces.List()
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Namespaces.List() error: %s\n", err)
            errStr := wski18n.T("Unable to obtain the list of available namespaces: {{.err}}",
                map[string]interface{}{"err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(namespaces)
        return nil
    },
}

var namespaceGetCmd = &cobra.Command{
    Use:   "get [NAMESPACE]",
    Short: wski18n.T("get triggers, actions, and rules in the registry for a namespace"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var qualifiedName QualifiedName
        var err error

        if whiskErr := checkArgs(args, 0, 1, "Namespace get",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        // Namespace argument is optional; defaults to configured property namespace
        if len(args) == 1 {
            if qualifiedName, err = parseQualifiedName(args[0]); err != nil {
                return parseQualifiedNameError(args[0], err)
            }

            if len(qualifiedName.entityName) > 0 {
                return entityNameError(qualifiedName.entityName)
            }
        }

        namespace, _, err := client.Namespaces.Get(qualifiedName.namespace)

        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Namespaces.Get(%s) error: %s\n", getClientNamespace(), err)
            errStr := wski18n.T("Unable to obtain the list of entities for namespace '{{.namespace}}': {{.err}}",
                    map[string]interface{}{"namespace": getClientNamespace(), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_NETWORK,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output, wski18n.T("Entities in namespace: {{.namespace}}\n",
            map[string]interface{}{"namespace": boldString(getClientNamespace())}))
        printList(namespace.Contents.Packages)
        printList(namespace.Contents.Actions)
        printList(namespace.Contents.Triggers)
        printList(namespace.Contents.Rules)

        return nil
    },
}

func init() {
    namespaceCmd.AddCommand(
        namespaceListCmd,
        namespaceGetCmd,
    )
}
