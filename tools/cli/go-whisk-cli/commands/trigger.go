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

    "github.com/spf13/cobra"
    "github.com/fatih/color"
)

const FEED_LIFECYCLE_EVENT  = "lifecycleEvent"
const FEED_TRIGGER_NAME     = "triggerName"
const FEED_AUTH_KEY         = "authKey"
const FEED_CREATE           = "CREATE"
const FEED_DELETE           = "DELETE"

// triggerCmd represents the trigger command
var triggerCmd = &cobra.Command{
    Use:   "trigger",
    Short: wski18n.T("work with triggers"),
}

var triggerFireCmd = &cobra.Command{
    Use:   "fire TRIGGER_NAME [PAYLOAD]",
    Short: wski18n.T("fire trigger event"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var parameters interface{}

        if whiskErr := checkArgs(args, 1, 2, "Trigger fire",
                wski18n.T("A trigger name is required. A payload is optional.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }

        client.Namespace = qName.namespace

        // Add payload to parameters
        if len(args) == 2 {
            flags.common.param = append(flags.common.param, getFormattedJSON("payload", args[1]))
            flags.common.param = append(flags.common.param, flags.common.param...)
        }

        if len(flags.common.param) > 0 {
            parameters, err = getJSONFromStrings(flags.common.param, false)
            if err != nil {
                whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, false) failed: %s\n", flags.common.param, err)
                errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                        map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
        }

        trigResp, _, err := client.Triggers.Fire(qName.entityName, parameters)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Fire(%s, %#v) failed: %s\n", qName.entityName, parameters, err)
            errStr := wski18n.T("Unable to fire trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} triggered /{{.namespace}}/{{.name}} with id {{.id}}\n",
                map[string]interface{}{
                    "ok": color.GreenString("ok:"),
                    "namespace": boldString(qName.namespace),
                    "name": boldString(qName.entityName),
                    "id": boldString(trigResp.ActivationId)}))
        return nil
    },
}

var triggerCreateCmd = &cobra.Command{
    Use:   "create TRIGGER_NAME",
    Short: wski18n.T("create new trigger"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var annotations interface{}
        var feedArgPassed bool = (flags.common.feed != "")

        if whiskErr := checkArgs(args, 1, 1, "Trigger create",
                wski18n.T("A trigger name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }

        client.Namespace = qName.namespace

        var fullTriggerName string
        var fullFeedName string
        if feedArgPassed {
            whisk.Debug(whisk.DbgInfo, "Trigger has a feed\n")
            feedqName, err := parseQualifiedName(flags.common.feed)
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", flags.common.feed, err)
                errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                        map[string]interface{}{"name": flags.common.feed, "err": err})
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }
            if len(feedqName.namespace) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", flags.common.feed)
                errStr := wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }

            fullFeedName = fmt.Sprintf("/%s/%s", feedqName.namespace, feedqName.entityName)
            fullTriggerName = fmt.Sprintf("/%s/%s", qName.namespace, qName.entityName)
            flags.common.param = append(flags.common.param, getFormattedJSON(FEED_LIFECYCLE_EVENT, FEED_CREATE))
            flags.common.param = append(flags.common.param, getFormattedJSON(FEED_TRIGGER_NAME, fullTriggerName))
            flags.common.param = append(flags.common.param, getFormattedJSON(FEED_AUTH_KEY, client.Config.AuthToken))
        }


        // Convert the trigger's list of default parameters from a string into []KeyValue
        // The 1 or more --param arguments have all been combined into a single []string
        // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]
        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := getJSONFromStrings(flags.common.param, !feedArgPassed)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.param, err)
            errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                    map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        // Add feed to annotations
        if feedArgPassed {
            flags.common.annotation = append(flags.common.annotation, getFormattedJSON("feed", flags.common.feed))
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err = getJSONFromStrings(flags.common.annotation, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.annotation, err)
            errStr := wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
                    map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        trigger := &whisk.Trigger{
            Name:        qName.entityName,
            Annotations: annotations.(whisk.KeyValueArr),
        }

        if !feedArgPassed {
            trigger.Parameters = parameters.(whisk.KeyValueArr)
        }

        _, _, err = client.Triggers.Insert(trigger, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Insert(%+v,false) failed: %s\n", trigger, err)
            errStr := wski18n.T("Unable to create trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": trigger.Name, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Invoke the specified feed action to configure the trigger feed
        if feedArgPassed {
            err := configureFeed(trigger.Name, fullFeedName)
            if err != nil {
                whisk.Debug(whisk.DbgError, "configureFeed(%s, %s) failed: %s\n", trigger.Name, flags.common.feed,
                    err)
                errStr := wski18n.T("Unable to create trigger '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": trigger.Name, "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

                // Delete trigger that was created for this feed
                delerr := deleteTrigger(args[0])
                if delerr != nil {
                    whisk.Debug(whisk.DbgWarn, "Ignoring deleteTrigger(%s) failure: %s\n", args[0], delerr)
                }
                return werr
            }
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} created trigger {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(trigger.Name)}))
        return nil
    },
}

var triggerUpdateCmd = &cobra.Command{
    Use:   "update TRIGGER_NAME",
    Short: wski18n.T("update an existing trigger, or create a trigger if it does not exist"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error

        if whiskErr := checkArgs(args, 1, 1, "Trigger update",
                wski18n.T("A trigger name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }

        client.Namespace = qName.namespace

        // Convert the trigger's list of default parameters from a string into []KeyValue
        // The 1 or more --param arguments have all been combined into a single []string
        // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]

        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := getJSONFromStrings(flags.common.param, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.param, err)
            errStr := wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                    map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := getJSONFromStrings(flags.common.annotation, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromStrings(%#v, true) failed: %s\n", flags.common.annotation, err)
            errStr := wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
                    map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        trigger := &whisk.Trigger{
            Name:        qName.entityName,
            Parameters:  parameters.(whisk.KeyValueArr),
            Annotations: annotations.(whisk.KeyValueArr),
        }

        _, _, err = client.Triggers.Insert(trigger, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Insert(%+v,true) failed: %s\n", trigger, err)
            errStr := wski18n.T("Unable to update trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": trigger.Name, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} updated trigger {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(trigger.Name)}))
        return nil
    },
}

var triggerGetCmd = &cobra.Command{
    Use:   "get TRIGGER_NAME [FIELD_FILTER]",
    Short: wski18n.T("get trigger"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var field string

        if whiskErr := checkArgs(args, 1, 2, "Trigger get", wski18n.T("A trigger name is required.")); whiskErr != nil {
            return whiskErr
        }

        if len(args) > 1 {
            field = args[1]

            if !fieldExists(&whisk.Trigger{}, field) {
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
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }

        client.Namespace = qName.namespace

        retTrigger, _, err := client.Triggers.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Get(%s) failed: %s\n", qName.entityName, err)
            errStr := wski18n.T("Unable to get trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        if (flags.trigger.summary) {
            printSummary(retTrigger)
        } else {
            if len(field) > 0 {
                fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got trigger {{.name}}, displaying field {{.field}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qName.entityName),
                    "field": boldString(field)}))
                printField(retTrigger, field)
            } else {
                fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got trigger {{.name}}\n",
                        map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qName.entityName)}))
                printJSON(retTrigger)
            }
        }

        return nil
    },
}

var triggerDeleteCmd = &cobra.Command{
    Use:   "delete TRIGGER_NAME",
    Short: wski18n.T("delete trigger"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        var retTrigger *whisk.Trigger
        var fullFeedName string
        var origParams []string

        if whiskErr := checkArgs(args, 1, 1, "Trigger delete",
                wski18n.T("A trigger name is required.")); whiskErr != nil {
            return whiskErr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err})
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }

        client.Namespace = qName.namespace

        retTrigger, _, err = client.Triggers.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Get(%s) failed: %s\n", qName.entityName, err)
            errStr := wski18n.T("Unable to get trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Get full feed name from trigger delete request as it is needed to delete the feed
        if retTrigger != nil && retTrigger.Annotations != nil {
            fullFeedName = getValueString(retTrigger.Annotations, "feed")

            if len(fullFeedName) > 0 {
                origParams = flags.common.param
                fullTriggerName := fmt.Sprintf("/%s/%s", qName.namespace, qName.entityName)
                flags.common.param = append(flags.common.param, getFormattedJSON(FEED_LIFECYCLE_EVENT, FEED_DELETE))
                flags.common.param = append(flags.common.param, getFormattedJSON(FEED_TRIGGER_NAME, fullTriggerName))
                flags.common.param = append(flags.common.param, getFormattedJSON(FEED_AUTH_KEY, client.Config.AuthToken))

                err = configureFeed(qName.entityName, fullFeedName)
                if err != nil {
                    whisk.Debug(whisk.DbgError, "configureFeed(%s, %s) failed: %s\n", qName.entityName, fullFeedName, err)
                }

                flags.common.param = origParams
                client.Namespace = qName.namespace
            }

        }

        retTrigger, _, err = client.Triggers.Delete(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Delete(%s) failed: %s\n", qName.entityName, err)
            errStr := wski18n.T("Unable to delete trigger '{{.name}}': {{.err}}",
                map[string]interface{}{"name": qName.entityName, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} deleted trigger {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qName.entityName)}))

        return nil
    },
}

var triggerListCmd = &cobra.Command{
    Use:   "list [NAMESPACE]",
    Short: wski18n.T("list all triggers"),
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
                errStr := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                        map[string]interface{}{"name": args[0], "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
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
            whisk.Debug(whisk.DbgInfo, "Using namespace '%s' from argument '%s''\n", ns, args[0])
        } else if whiskErr := checkArgs(args, 0, 1, "Trigger list",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        options := &whisk.TriggerListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }
        triggers, _, err := client.Triggers.List(options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.List(%#v) for namespace '%s' failed: %s\n", options,
                client.Namespace, err)
            errStr := wski18n.T("Unable to obtain the list of triggers for namespace '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": getClientNamespace(), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(triggers)
        return nil
    },
}

func configureFeed(triggerName string, FullFeedName string) error {
    feedArgs := []string {FullFeedName}
    flags.common.blocking = true
    err := actionInvokeCmd.RunE(nil, feedArgs)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Invoke of action '%s' failed: %s\n", FullFeedName, err)
        errStr := wski18n.T("Unable to invoke trigger '{{.trigname}}' feed action '{{.feedname}}'; feed is not configured: {{.err}}",
                map[string]interface{}{"trigname": triggerName, "feedname": FullFeedName, "err": err})
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    } else {
        whisk.Debug(whisk.DbgInfo, "Successfully configured trigger feed via feed action '%s'\n", FullFeedName)
    }

    return err
}

func deleteTrigger(triggerName string) error {
    args := []string {triggerName}
    err := triggerDeleteCmd.RunE(nil, args)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Trigger '%s' delete failed: %s\n", triggerName, err)
        errStr := wski18n.T("Unable to delete trigger '{{.name}}': {{.err}}",
                map[string]interface{}{"name": triggerName, "err": err})
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    }

    return err
}

func init() {
    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
    triggerCreateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
    triggerCreateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))
    triggerCreateCmd.Flags().StringVarP(&flags.common.feed, "feed", "f", "", wski18n.T("trigger feed `ACTION_NAME`"))

    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
    triggerUpdateCmd.Flags().StringVarP(&flags.common.annotFile, "annotation-file", "A", "", wski18n.T("`FILE` containing annotation values in JSON format"))
    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
    triggerUpdateCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))

    triggerGetCmd.Flags().BoolVarP(&flags.trigger.summary, "summary", "s", false, wski18n.T("summarize trigger details"))

    triggerFireCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))
    triggerFireCmd.Flags().StringVarP(&flags.common.paramFile, "param-file", "P", "", wski18n.T("`FILE` containing parameter values in JSON format"))

    triggerListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of triggers from the result"))
    triggerListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of triggers from the collection"))

    triggerCmd.AddCommand(
        triggerFireCmd,
        triggerCreateCmd,
        triggerUpdateCmd,
        triggerGetCmd,
        triggerDeleteCmd,
        triggerListCmd,
    )

}
