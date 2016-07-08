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

    "../../go-whisk/whisk"

    "github.com/fatih/color"
    "github.com/spf13/cobra"
)

// ruleCmd represents the rule command
var ruleCmd = &cobra.Command{
    Use:   "rule",
    Short: "work with rules",
}

var ruleEnableCmd = &cobra.Command{
    Use:   "enable <name string>",
    Short: "enable rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName

        _, _, err = client.Rules.SetState(ruleName, "active")
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, active) failed: %s\n", ruleName, err)
            errStr := fmt.Sprintf("Unable to enable rule '%s': %s", ruleName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s enabled rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        return nil
    },
}

var ruleDisableCmd = &cobra.Command{
    Use:   "disable <name string>",
    Short: "disable rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName

        _, _, err = client.Rules.SetState(ruleName, "inactive")
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, inactive) failed: %s\n", ruleName, err)
            errStr := fmt.Sprintf("Unable to disable rule '%s': %s", ruleName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s disabled rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        return nil
    },
}

var ruleStatusCmd = &cobra.Command{
    Use:   "status <name string>",
    Short: "get rule status",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName

        rule, _, err := client.Rules.Get(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Get(%s) failed: %s\n", ruleName, err)
            errStr := fmt.Sprintf("Unable to retrieve rule '%s': %s", ruleName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s rule %s is %s\n", color.GreenString("ok:"), boldString(ruleName), boldString(rule.Status))
        return nil
    },
}

var ruleCreateCmd = &cobra.Command{
    Use:   "create <name string> <trigger string> <action string>",
    Short: "create new rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var shared bool

        if len(args) != 3 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly three arguments are expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        if (flags.common.shared == "yes") {
            shared = true
        } else {
            shared = false
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName
        triggerName := args[1]  // MWD qualified name here?
        actionName := args[2]   // MWD qualified name here?

        rule := &whisk.Rule{
            Name:    ruleName,
            Trigger: triggerName,
            Action:  actionName,
            Publish: shared,
        }

        whisk.Debug(whisk.DbgInfo, "Inserting rule:\n%+v\n", rule)
        var retRule *whisk.Rule
        retRule, _, err = client.Rules.Insert(rule, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Insert(%#v) failed: %s\n", rule, err)
            errStr := fmt.Sprintf("Unable to create rule '%s': %s", rule.Name, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        whisk.Debug(whisk.DbgInfo, "Inserted rule:\n%+v\n", retRule)

        if flags.rule.enable {
            retRule, _, err = client.Rules.SetState(ruleName, "active")
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, active) failed: %s\n", ruleName, err)
                // MWD - Is this a hard error since the rule was actually created
                errStr := fmt.Sprintf("Unable to enable created rule '%s': %s", rule.Name, err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }
        whisk.Debug(whisk.DbgInfo, "Enabled rule:\n%+v\n", retRule)

        fmt.Printf("%s created rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        return nil
    },
}

var ruleUpdateCmd = &cobra.Command{
    Use:   "update <name string> <trigger string> <action string>",
    Short: "update existing rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var shared bool

        if len(args) != 3 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly three arguments are expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName
        triggerName := args[1]  //MWD qualified name?
        actionName := args[2]   //MWD qualified name?

        if (flags.common.shared == "yes") {
            shared = true
        } else {
            shared = false
        }

        rule := &whisk.Rule{
            Name:    ruleName,
            Trigger: triggerName,
            Action:  actionName,
            Publish: shared,
        }

        rule, _, err = client.Rules.Insert(rule, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Insert(%#v) failed: %s\n", rule, err)
            errStr := fmt.Sprintf("Unable to update rule '%s': %s", rule.Name, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s updated rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        return nil
    },
}

var ruleGetCmd = &cobra.Command{
    Use:   "get <name string>",
    Short: "get rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName

        rule, _, err := client.Rules.Get(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Get(%s) failed: %s\n", ruleName, err)
            errStr := fmt.Sprintf("Unable to retrieve rule '%s': %s", ruleName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s got rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        //printJSON(rule)
        printJsonNoColor(rule)

        return nil
    },
}

var ruleDeleteCmd = &cobra.Command{
    Use:   "delete <name string>",
    Short: "delete rule",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
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
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace
        ruleName := qName.entityName

        if flags.rule.disable {
            _, _, err := client.Rules.SetState(ruleName, "inactive")
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, inactive) failed: %s\n", ruleName, err)
                errStr := fmt.Sprintf("Unable to disable rule '%s': %s", ruleName, err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        _, err = client.Rules.Delete(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Delete(%s) error: %s\n", ruleName, err)
            errStr := fmt.Sprintf("Unable to delete rule '%s': %s", ruleName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("%s deleted rule %s\n", color.GreenString("ok:"), boldString(ruleName))
        return nil
    },
}

var ruleListCmd = &cobra.Command{
    Use:   "list <namespace string>",
    Short: "list all rules",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        qName := qualifiedName{}
        if len(args) == 1 {
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errStr := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
                errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
            client.Namespace = ns

            if pkg := qName.packageName; len(pkg) > 0 {
                // todo :: scope call to package
            }
        }

        ruleListOptions := &whisk.RuleListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }

        rules, _, err := client.Rules.List(ruleListOptions)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.List(%#v) error: %s\n", ruleListOptions, err)
            errStr := fmt.Sprintf("Unable to obtain list of rules: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(rules)
        return nil
    },
}

func init() {

    ruleCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "shared action (yes = shared, no[default] = private)")
    ruleCreateCmd.Flags().BoolVar(&flags.rule.enable, "enable", false, "autmatically enable rule after creating it")

    ruleUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "shared action (yes = shared, no[default] = private)")

    ruleDeleteCmd.Flags().BoolVar(&flags.rule.disable, "disable", false, "autmatically disable rule before deleting it")

    ruleListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "skip this many entities from the head of the collection")
    ruleListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return this many entities from the collection")

    ruleCmd.AddCommand(
        ruleCreateCmd,
        ruleEnableCmd,
        ruleDisableCmd,
        ruleStatusCmd,
        ruleUpdateCmd,
        ruleGetCmd,
        ruleDeleteCmd,
        ruleListCmd,
    )

}
