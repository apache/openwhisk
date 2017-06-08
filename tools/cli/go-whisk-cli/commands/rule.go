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
    "../wski18n"

    "github.com/fatih/color"
    "github.com/spf13/cobra"
)

// ruleCmd represents the rule command
var ruleCmd = &cobra.Command{
    Use:   "rule",
    Short: wski18n.T("work with rules"),
}

var ruleEnableCmd = &cobra.Command{
    Use:   "enable RULE_NAME",
    Short: wski18n.T("enable rule"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 1, 1, "Rule enable", wski18n.T("A rule name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName

        _, _, err = client.Rules.SetState(ruleName, "active")
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, active) failed: %s\n", ruleName, err)
            errStr := wski18n.T("Unable to enable rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} enabled rule {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
        return nil
    },
}

var ruleDisableCmd = &cobra.Command{
    Use:   "disable RULE_NAME",
    Short: wski18n.T("disable rule"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 1, 1, "Rule disable", wski18n.T("A rule name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName

        _, _, err = client.Rules.SetState(ruleName, "inactive")
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, inactive) failed: %s\n", ruleName, err)
            errStr := wski18n.T("Unable to disable rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} disabled rule {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
        return nil
    },
}

var ruleStatusCmd = &cobra.Command{
    Use:   "status RULE_NAME",
    Short: wski18n.T("get rule status"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 1, 1, "Rule status", wski18n.T("A rule name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName

        rule, _, err := client.Rules.Get(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Get(%s) failed: %s\n", ruleName, err)
            errStr := wski18n.T("Unable to get status of rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} rule {{.name}} is {{.status}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName), "status": boldString(rule.Status)}))
        return nil
    },
}

var ruleCreateCmd = &cobra.Command{
    Use:   "create RULE_NAME TRIGGER_NAME ACTION_NAME",
    Short: wski18n.T("create new rule"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 3, 3, "Rule create",
                wski18n.T("A rule, trigger and action name are required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName
        triggerName := getQualifiedName(args[1], Properties.Namespace)
        actionName := getQualifiedName(args[2], Properties.Namespace)

        rule := &whisk.Rule{
            Name:    ruleName,
            Trigger: triggerName,
            Action:  actionName,
        }

        whisk.Debug(whisk.DbgInfo, "Inserting rule:\n%+v\n", rule)
        var retRule *whisk.Rule
        retRule, _, err = client.Rules.Insert(rule, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Insert(%#v) failed: %s\n", rule, err)
            errStr := wski18n.T("Unable to create rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        whisk.Debug(whisk.DbgInfo, "Inserted rule:\n%+v\n", retRule)

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} created rule {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
        return nil
    },
}

var ruleUpdateCmd = &cobra.Command{
    Use:   "update RULE_NAME TRIGGER_NAME ACTION_NAME",
    Short: wski18n.T("update an existing rule, or create a rule if it does not exist"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 3, 3, "Rule update",
                wski18n.T("A rule, trigger and action name are required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName
        triggerName := getQualifiedName(args[1], Properties.Namespace)
        actionName := getQualifiedName(args[2], Properties.Namespace)

        rule := &whisk.Rule{
            Name:    ruleName,
            Trigger: triggerName,
            Action:  actionName,
        }

        _, _, err = client.Rules.Insert(rule, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Insert(%#v) failed: %s\n", rule, err)
            errStr := wski18n.T("Unable to update rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": rule.Name, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} updated rule {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
        return nil
    },
}

var ruleGetCmd = &cobra.Command{
    Use:   "get RULE_NAME",
    Short: wski18n.T("get rule"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var field string

        if whiskErr := checkArgs(args, 1, 2, "Rule get", wski18n.T("A rule name is required.")); whiskErr != nil {
            return whiskErr
        }

        if len(args) > 1 {
            field = args[1]

            if !fieldExists(&whisk.Rule{}, field){
                errMsg := wski18n.T("Invalid field filter '{{.arg}}'.", map[string]interface{}{"arg": field})
                whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName

        rule, _, err := client.Rules.Get(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Get(%s) failed: %s\n", ruleName, err)
            errStr := wski18n.T("Unable to get rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        if (flags.rule.summary) {
            printRuleSummary(rule)
        } else {
            if len(field) > 0 {
                fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got rule {{.name}}, displaying field {{.field}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName),
                        "field": field}))
                printField(rule, field)
            } else {
                fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got rule {{.name}}\n",
                        map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
                printJSON(rule)
            }
        }

        return nil
    },
}

var ruleDeleteCmd = &cobra.Command{
    Use:   "delete RULE_NAME",
    Short: wski18n.T("delete rule"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 1, 1, "Rule delete", wski18n.T("A rule name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        client.Namespace = qName.namespace
        ruleName := qName.entityName

        if flags.rule.disable {
            _, _, err := client.Rules.SetState(ruleName, "inactive")
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Rules.SetState(%s, inactive) failed: %s\n", ruleName, err)
                errStr := wski18n.T("Unable to disable rule '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": ruleName, "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        _, err = client.Rules.Delete(ruleName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.Delete(%s) error: %s\n", ruleName, err)
            errStr := wski18n.T("Unable to delete rule '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": ruleName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} deleted rule {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(ruleName)}))
        return nil
    },
}

var ruleListCmd = &cobra.Command{
    Use:   "list [NAMESPACE]",
    Short: wski18n.T("list all rules"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        qName := QualifiedName{}

        if len(args) == 1 {
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errStr := wski18n.T("Namespace '{{.name}}' is invalid: {{.err}}\n",
                        map[string]interface{}{"name": args[0], "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
                errStr := wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
            client.Namespace = ns
        } else if whiskErr := checkArgs(args, 0, 1, "Rule list",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        ruleListOptions := &whisk.RuleListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }

        rules, _, err := client.Rules.List(ruleListOptions)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Rules.List(%#v) error: %s\n", ruleListOptions, err)
            errStr := wski18n.T("Unable to obtain the list of rules for namespace '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": getClientNamespace(), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(rules)
        return nil
    },
}

func init() {
    ruleDeleteCmd.Flags().BoolVar(&flags.rule.disable, "disable", false, wski18n.T("automatically disable rule before deleting it"))

    ruleGetCmd.Flags().BoolVarP(&flags.rule.summary, "summary", "s", false, wski18n.T("summarize rule details"))

    ruleListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of rules from the result"))
    ruleListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of rules from the collection"))

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
