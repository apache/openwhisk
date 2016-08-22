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
    "encoding/json"
    "errors"
    "fmt"
    "strings"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/spf13/cobra"
    "github.com/fatih/color"
)

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
        var payloadArg string
        if len(args) < 1 || len(args) > 2 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 or 2); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; 1 or 2 arguments are expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf(
                wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf(
                wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        var payload *json.RawMessage

        if len(flags.common.param) > 0 {
            parameters, err := getJSONFromArguments(flags.common.param, false)
            if err != nil {
                whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, false) failed: %s\n", flags.common.param, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                        map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err}))
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }

            payload = parameters
        }

        if payload == nil {
            data := []byte("{}")
            payload = (*json.RawMessage)(&data)
        }

        if len(args) == 2 {
            payloadArg = args[1]
            reader := strings.NewReader(payloadArg)
            err = json.NewDecoder(reader).Decode(&payload)
            if err != nil {
                tmpPayload := "{\"payload\": \"" + payloadArg + "\"}"
                data := []byte(tmpPayload)
                payload = (*json.RawMessage)(&data)
                whisk.Debug(whisk.DbgError, "json.NewDecoder().Decode() failure decoding '%s': : %s\n", payloadArg, err)
                whisk.Debug(whisk.DbgWarn, "Defaulting payload to %#v\n", payload)
            }
        }

        trigResp, _, err := client.Triggers.Fire(qName.entityName, payload)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Fire(%s, %#v) failed: %s\n", qName.entityName, payload, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to fire trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
        var feedArgPassed bool = (flags.common.feed != "")
        var feedParams []string

        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; exactly one argument is expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf(
                wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf(
                wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        // Convert the trigger's list of default parameters from a string into []KeyValue
        // The 1 or more --param arguments have all been combined into a single []string
        // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]
        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := getJSONFromArguments(flags.common.param, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                    map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        var fullTriggerName string
        var fullFeedName string
        if feedArgPassed {
            whisk.Debug(whisk.DbgInfo, "Trigger has a feed\n")
            feedqName, err := parseQualifiedName(flags.common.feed)
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", flags.common.feed, err)
                errMsg := fmt.Sprintf(
                    wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                        map[string]interface{}{"name": flags.common.feed, "err": err}))
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }
            if len(feedqName.namespace) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", flags.common.feed)
                errStr := fmt.Sprintf(
                    wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }

            feedParams = flags.common.param
            fullFeedName = fmt.Sprintf("/%s/%s", feedqName.namespace, feedqName.entityName)

            feedParams = append(feedParams, "lifecycleEvent")
            feedParams = append(feedParams, "CREATE")

            fullTriggerName = fmt.Sprintf("/%s/%s", qName.namespace, qName.entityName)
            feedParams = append(feedParams, "triggerName")
            feedParams = append(feedParams, fullTriggerName)


            feedParams = append(feedParams, "authKey")
            feedParams = append(feedParams, client.Config.AuthToken)

            parameters = nil
            whisk.Debug(whisk.DbgInfo, "Trigger feed action parameters: %#v\n", feedParams)
        }

        var annotations *json.RawMessage
        if feedArgPassed {
            feedAnnotations := []string{"feed", flags.common.feed}
            feedAnnotations = append(feedAnnotations, flags.common.annotation...)
            whisk.Debug(whisk.DbgInfo, "Parsing trigger feed annotations: %#v\n", feedAnnotations)
            annotations, err = getJSONFromArguments(feedAnnotations, true)
        } else {
            whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
            annotations, err = getJSONFromArguments(flags.common.annotation, true)
        }

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
                    map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Trigger shared: %s\n", flags.common.shared)
        shared := strings.ToLower(flags.common.shared)
        if shared != "yes" && shared != "no" && shared != "" {  // "" means argument was not specified
            whisk.Debug(whisk.DbgError, "Shared argument value '%s' is invalid\n", flags.common.shared)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid --shared argument value '{{.argval}}'; valid values are 'yes' or 'no'",
                    map[string]interface{}{"argval": flags.common.shared}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), nil, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        publish := false
        if shared == "yes" {
            publish = true
        }

        trigger := &whisk.Trigger{
            Name:        qName.entityName,
            Parameters:  parameters,
            Annotations: annotations,
            Publish:     publish,
        }

        _, _, err = client.Triggers.Insert(trigger, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Insert(%+v,false) failed: %s\n", trigger, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to create trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": trigger.Name, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Invoke the specified feed action to configure the trigger feed
        if feedArgPassed {
            err := createFeed(trigger.Name, fullFeedName, feedParams)
            if err != nil {
                whisk.Debug(whisk.DbgError, "createFeed(%s, %s, %+v) failed: %s\n", trigger.Name, flags.common.feed, feedParams, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to create trigger '{{.name}}': {{.err}}",
                        map[string]interface{}{"name": trigger.Name, "err": err}))
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
    Short: wski18n.T("update existing trigger"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; exactly one argument is expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf(
                wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf(
                wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        // Convert the trigger's list of default parameters from a string into []KeyValue
        // The 1 or more --param arguments have all been combined into a single []string
        // e.g.   --p arg1,arg2 --p arg3,arg4   ->  [arg1, arg2, arg3, arg4]

        whisk.Debug(whisk.DbgInfo, "Parsing parameters: %#v\n", flags.common.param)
        parameters, err := getJSONFromArguments(flags.common.param, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.param, err)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid parameter argument '{{.param}}': {{.err}}",
                    map[string]interface{}{"param": fmt.Sprintf("%#v",flags.common.param), "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := getJSONFromArguments(flags.common.annotation, true)

        if err != nil {
            whisk.Debug(whisk.DbgError, "getJSONFromArguments(%#v, true) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid annotation argument '{{.annotation}}': {{.err}}",
                    map[string]interface{}{"annotation": fmt.Sprintf("%#v",flags.common.annotation), "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Trigger shared: %s\n", flags.common.shared)
        shared := strings.ToLower(flags.common.shared)

        if shared != "yes" && shared != "no" && shared != "" {  // "" means argument was not specified
            whisk.Debug(whisk.DbgError, "Shared argument value '%s' is invalid\n", flags.common.shared)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid --shared argument value '{{.argval}}'; valid values are 'yes' or 'no'",
                    map[string]interface{}{"argval": flags.common.shared}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), nil, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        publishSet := false
        publish := false
        if shared == "yes" {
            publishSet = true
            publish = true
        } else if shared == "no" {
            publishSet = true
            publish = false
        }

        trigger := &whisk.Trigger{
            Name:        qName.entityName,
            Parameters:  parameters,
            Annotations: annotations,
        }
        if publishSet {
            trigger.Publish = publish
        }

        retTrigger, _, err := client.Triggers.Insert(trigger, true)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Insert(%+v,true) failed: %s\n", trigger, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to update trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": trigger.Name, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output,
            wski18n.T("{{.ok}} updated trigger {{.name}}\n",
                map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(trigger.Name)}))
        printJSON(retTrigger)
        return nil
    },
}

var triggerGetCmd = &cobra.Command{
    Use:   "get TRIGGER_NAME",
    Short: wski18n.T("get trigger"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; exactly one argument is expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf(
                wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf(
                wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        retTrigger, _, err := client.Triggers.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Get(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to get trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        if (flags.trigger.summary) {
            fmt.Fprintf(color.Output,
                wski18n.T("trigger /{{.namespace}}/{{.name}}\n",
                    map[string]interface{}{"namespace": retTrigger.Namespace, "name": retTrigger.Name}))
        } else {
            fmt.Fprintf(color.Output,
                wski18n.T("{{.ok}} got trigger {{.name}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(qName.entityName)}))
            printJSON(retTrigger)
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
        var feedParams []string
        var retTrigger *whisk.TriggerFromServer
        var fullFeedName string

        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; exactly one argument is expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf(
                wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                    map[string]interface{}{"name": args[0], "err": err}))
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf(
                wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        retTrigger, _, err = client.Triggers.Delete(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Delete(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to delete trigger '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": qName.entityName, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Get full feed name from trigger delete request as it is needed to delete the feed
        if retTrigger != nil && retTrigger.Annotations != nil {
            fullFeedName = getValueFromAnnotations(retTrigger.Annotations, "feed")

            if len(fullFeedName) > 0 {
                feedParams = append(feedParams, "lifecycleEvent")
                feedParams = append(feedParams, "DELETE")

                fullTriggerName := fmt.Sprintf("/%s/%s", qName.namespace, qName.entityName)
                feedParams = append(feedParams, "triggerName")
                feedParams = append(feedParams, fullTriggerName)

                feedParams = append(feedParams, "authKey")
                feedParams = append(feedParams, client.Config.AuthToken)

                err = deleteFeed(qName.entityName, fullFeedName, feedParams)
                if err != nil {
                    whisk.Debug(whisk.DbgError, "deleteFeed(%s, %s, %+v) failed: %s\n", qName.entityName, flags.common.feed, feedParams, err)
                    errStr := fmt.Sprintf(
                        wski18n.T("Unable to delete trigger '{{.name}}': {{.err}}",
                            map[string]interface{}{"name": qName.entityName, "err": err}))
                    werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

                    return werr
                }
            }
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
        qName := qualifiedName{}
        if len(args) == 1 {
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errStr := fmt.Sprintf(
                    wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                        map[string]interface{}{"name": args[0], "err": err}))
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
                errStr := fmt.Sprintf(
                    wski18n.T("No valid namespace detected. Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\""))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
            client.Namespace = ns
            whisk.Debug(whisk.DbgInfo, "Using namespace '%s' from argument '%s''\n", ns, args[0])
        } else if len(args) > 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf(
                wski18n.T("Invalid number of arguments ({{.argnum}}) provided; at most one argument is expected",
                    map[string]interface{}{"argnum": len(args)}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        options := &whisk.TriggerListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }
        triggers, _, err := client.Triggers.List(options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.List(%#v) for namespace '%s' failed: %s\n", options, client.Namespace, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to obtain the trigger list for namespace '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": client.Namespace, "err": err}))
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        printList(triggers)
        return nil
    },
}

func createFeed (triggerName string, FullFeedName string, parameters []string) error {
    var originalParams = flags.common.param

    // Invoke the feed action to configure the feed
    feedArgs := []string {FullFeedName}
    flags.common.param = parameters
    flags.common.blocking = true
    err := actionInvokeCmd.RunE(nil, feedArgs)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Invoke of action '%s' failed: %s\n", FullFeedName, err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to invoke trigger '{{.trigname}}' feed action '{{.feedname}}'; feed is not configured: {{.err}}",
                map[string]interface{}{"trigname": triggerName, "feedname": FullFeedName, "err": err}))
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    } else {
        whisk.Debug(whisk.DbgInfo, "Successfully configured trigger feed via feed action '%s'\n", FullFeedName)
    }

    flags.common.param = originalParams
    return err
}

func deleteFeed (triggerName string, FullFeedName string, parameters []string) error {
    var originalParams = flags.common.param

    feedArgs := []string {FullFeedName}
    flags.common.param = parameters
    flags.common.blocking = true
    err := actionInvokeCmd.RunE(nil, feedArgs)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Invoke of action '%s' failed: %s\n", FullFeedName, err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to invoke trigger '{{.trigname}}' feed action '{{.feedname}}'; feed is not configured: {{.err}}",
                map[string]interface{}{"trigname": triggerName, "feedname": FullFeedName, "err": err}))
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    } else {
        whisk.Debug(whisk.DbgInfo, "Successfully configured trigger feed via feed action '%s'\n", FullFeedName)
    }

    flags.common.param = originalParams
    return err
}

func deleteTrigger (triggerName string) error {
    args := []string {triggerName}
    err := triggerDeleteCmd.RunE(nil, args)
    if err != nil {
        whisk.Debug(whisk.DbgError, "Trigger '%s' delete failed: %s\n", triggerName, err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to delete trigger '{{.name}}': {{.err}}",
                map[string]interface{}{"name": triggerName, "err": err}))
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    }

    return err
}

func init() {

    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("default parameter values in `KEY VALUE` format"))
    triggerCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "no", wski18n.T("trigger visibility `SCOPE`; yes = shared, no = private"))
    triggerCreateCmd.Flags().StringVarP(&flags.common.feed, "feed", "f", "", wski18n.T("trigger feed `ACTION_NAME`"))

    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, wski18n.T("annotation values in `KEY VALUE` format"))
    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("default parameter values in `KEY VALUE` format"))
    triggerUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", wski18n.T("trigger visibility `SCOPE`; yes = shared, no = private"))

    triggerGetCmd.Flags().BoolVarP(&flags.trigger.summary, "summary", "s", false, wski18n.T("summarize trigger details"))

    triggerFireCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, wski18n.T("parameter values in `KEY VALUE` format"))

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
