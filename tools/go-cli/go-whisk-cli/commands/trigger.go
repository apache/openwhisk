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

    "github.com/spf13/cobra"
)

// triggerCmd represents the trigger command
var triggerCmd = &cobra.Command{
    Use:   "trigger",
    Short: "work with triggers",
}

var triggerFireCmd = &cobra.Command{
    Use:   "fire <name string> <payload string>",
    Short: "fire trigger event",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        var payloadArg string
        if len(args) < 1 || len(args) > 2 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d (expected 1 or 2); args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; 1 or 2 arguments are expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        var payload *json.RawMessage

        if len(flags.common.param) > 0 {
            parameters, err := parseParameters(flags.common.param)
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseParameters(%#v) failed: %s\n", flags.common.param, err)
                errStr := fmt.Sprintf("Invalid parameter argument '%#v': %s", flags.common.param, err)
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
            errStr := fmt.Sprintf("Unable to fire trigger '%s': %s", qName.entityName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Printf("ok: triggered %s with id %s\n", qName.entityName, trigResp.ActivationId )
        return nil
    },
}

var triggerCreateCmd = &cobra.Command{
    Use:   "create",
    Short: "create new trigger",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        var feedArgPassed bool = (flags.common.feed != "")
        var feedParams []string

        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        // Convert the trigger's list of default parameters from a string into []KeyValue
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

        var fullTriggerName string
        var fullFeedName string
        if feedArgPassed {
            whisk.Debug(whisk.DbgInfo, "Trigger has a feed\n")
            feedqName, err := parseQualifiedName(flags.common.feed)
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", flags.common.feed, err)
                errMsg := fmt.Sprintf("Failed to parse qualified feed name: %s", flags.common.feed)
                whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return whiskErr
            }
            if len(feedqName.namespace) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", flags.common.feed)
                errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
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
            feedParams = append(feedParams, flags.global.auth)  // MWD ?? only from CLI arg

            //parameters = whisk.Parameters{}
            parameters = nil
            whisk.Debug(whisk.DbgInfo, "Trigger feed action parameters: %#v\n", feedParams)
        }

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := parseAnnotations(flags.common.annotation)
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAnnotations(%#v) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf("Invalid annotations argument value '%#v': %s", flags.common.annotation, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        if feedArgPassed {
            feedAnnotation := make(map[string]interface{}, 0)
            feedAnnotation["key"] = "feed"
            feedAnnotation["value"] = flags.common.feed
            annotations = append(annotations, feedAnnotation)
            whisk.Debug(whisk.DbgInfo, "Trigger feed annotations: %#v\n", annotations)
        }

        whisk.Debug(whisk.DbgInfo, "Trigger shared: %s\n", flags.common.shared)
        shared := strings.ToLower(flags.common.shared)
        if shared != "yes" && shared != "no" && shared != "" {  // "" means argument was not specified
            whisk.Debug(whisk.DbgError, "Shared argument value '%s' is invalid\n", flags.common.shared)
            errStr := fmt.Sprintf("Invalid --shared argument value '%s'; valid values are 'yes' or 'no'", flags.common.shared)
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

        retTrigger, _, err := client.Triggers.Insert(trigger, false)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Insert(%+v,false) failed: %s\n", trigger, err)
            errStr := fmt.Sprintf("Unable to create trigger '%s': %s", trigger.Name, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Invoke the specified feed action to configure the trigger feed
        if feedArgPassed {
            err := createFeed(trigger.Name, fullFeedName, feedParams)
            if err != nil {
                whisk.Debug(whisk.DbgError, "createFeed(%s, %s, %+v) failed: %s\n", trigger.Name, flags.common.feed, feedParams, err)
                errStr := fmt.Sprintf("Unable to create trigger '%s': %s", trigger.Name, err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

                // Delete trigger that was created for this feed
                delerr := deleteTrigger(args[0])
                if delerr != nil {
                    whisk.Debug(whisk.DbgWarn, "Ignoring deleteTrigger(%s) failure: %s\n", args[0], delerr)
                }
                return werr
            }
        }

        fmt.Println("ok: created trigger")
        //MWD printJSON(retTrigger) // color does appear correctly on vagrant VM
        printJsonNoColor(retTrigger)
        return nil
    },
}

var triggerUpdateCmd = &cobra.Command{
    Use:   "update",
    Short: "update existing trigger",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        // Convert the trigger's list of default parameters from a string into []KeyValue
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

        whisk.Debug(whisk.DbgInfo, "Parsing annotations: %#v\n", flags.common.annotation)
        annotations, err := parseAnnotations(flags.common.annotation)

        if err != nil {
            whisk.Debug(whisk.DbgError, "parseAnnotations(%#v) failed: %s\n", flags.common.annotation, err)
            errStr := fmt.Sprintf("Invalid annotations argument value '%#v': %s", flags.common.annotation, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        whisk.Debug(whisk.DbgInfo, "Trigger shared: %s\n", flags.common.shared)
        shared := strings.ToLower(flags.common.shared)

        if shared != "yes" && shared != "no" && shared != "" {  // "" means argument was not specified
            whisk.Debug(whisk.DbgError, "Shared argument value '%s' is invalid\n", flags.common.shared)
            errStr := fmt.Sprintf("Invalid --shared argument value '%s'; valid values are 'yes' or 'no'", flags.common.shared)
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
            errStr := fmt.Sprintf("Unable to update trigger '%s': %s", trigger.Name, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Println("ok: updated trigger")
        //MWD printJSON(retTrigger) // color does appear correctly on vagrant VM
        printJsonNoColor(retTrigger)
        return nil
    },
}

var triggerGetCmd = &cobra.Command{
    Use:   "get <name string>",
    Short: "get trigger",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments %d; args: %#v\n", len(args), args)
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        retTrigger, _, err := client.Triggers.Get(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Get(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf("Unable to get trigger '%s': %s", qName.entityName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
        fmt.Printf("ok: got trigger %s\n", qName.entityName)
        //MWD printJSON(retTrigger) // color does appear correctly on vagrant VM
        printJsonNoColor(retTrigger)
        return nil
    },
}

var triggerDeleteCmd = &cobra.Command{
    Use:   "delete <name string>",
    Short: "delete trigger",
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
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        qName, err := parseQualifiedName(args[0])
        if err != nil {
            whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
            errMsg := fmt.Sprintf("Failed to parse qualified name: %s", args[0])
            whiskErr := whisk.MakeWskErrorFromWskError(errors.New(errMsg), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

            return whiskErr
        }
        if len(qName.namespace) == 0 {
            whisk.Debug(whisk.DbgError, "Namespace is missing from '%s'\n", args[0])
            errStr := fmt.Sprintf("No valid namespace detected.  Run 'wsk property set --namespace' or ensure the name argument is preceded by a \"/\"")
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        client.Namespace = qName.namespace

        retTrigger, _, err = client.Triggers.Delete(qName.entityName)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.Delete(%s) failed: %s\n", qName.entityName, err)
            errStr := fmt.Sprintf("Unable to delete trigger '%s': %s", qName.entityName, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // Get full feed name from trigger delete request as it is needed to delete the feed
        if retTrigger != nil && retTrigger.Annotations != nil {
            for i := 0; i < len(retTrigger.Annotations); i++ {

                switch key := retTrigger.Annotations[i]["key"].(type) {
                    case string: {
                        if key == "feed" {
                            switch value := retTrigger.Annotations[i]["value"].(type) {
                                case string: {
                                    fullFeedName = value
                                }
                            }
                        }
                    }
                }
            }
        }

        if len(fullFeedName) > 0 {
            feedParams = append(feedParams, "lifecycleEvent")
            feedParams = append(feedParams, "DELETE")

            fullTriggerName := fmt.Sprintf("/%s/%s", qName.namespace, qName.entityName)
            feedParams = append(feedParams, "triggerName")
            feedParams = append(feedParams, fullTriggerName)

            feedParams = append(feedParams, "authKey")
            feedParams = append(feedParams, flags.global.auth)  // MWD ?? only from CLI arg

            err = deleteFeed(qName.entityName, fullFeedName, feedParams)
            if err != nil {
                whisk.Debug(whisk.DbgError, "deleteFeed(%s, %s, %+v) failed: %s\n", qName.entityName, flags.common.feed, feedParams, err)
                errStr := fmt.Sprintf("Unable to delete trigger '%s': %s", qName.entityName, err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)

                return werr
            }
        }

        fmt.Printf("ok: deleted trigger %s\n", qName.entityName)
        return nil
    },
}

var triggerListCmd = &cobra.Command{
    Use:   "list <namespace string>",
    Short: "list all triggers",
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
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
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
            whisk.Debug(whisk.DbgInfo, "Using namespace '%s' from argument '%s''\n", ns, args[0])

            if pkg := qName.packageName; len(pkg) > 0 {
                // todo :: scope call to package
            }
        }

        options := &whisk.TriggerListOptions{
            Skip:  flags.common.skip,
            Limit: flags.common.limit,
        }
        triggers, _, err := client.Triggers.List(options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Triggers.List(%#v) for namespace '%s' failed: %s\n", options, client.Namespace, err)
            errStr := fmt.Sprintf("Unable to obtain the trigger list for namespace '%s': %s", client.Namespace, err)
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
        errStr := fmt.Sprintf("Unable to invoke trigger '%s' feed action '%s'; feed is not configured: %s", triggerName, FullFeedName, err)
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
        errStr := fmt.Sprintf("Unable to invoke trigger '%s' feed action '%s'; feed is not configured: %s", triggerName, FullFeedName, err)
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
        errStr := fmt.Sprintf("Unable to delete trigger '%s': %s", triggerName, err)
        err = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
    }

    return err
}

func init() {

    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    triggerCreateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")
    triggerCreateCmd.Flags().StringVar(&flags.common.shared, "shared", "no", "shared action [yes|no]")
    triggerCreateCmd.Flags().StringVarP(&flags.common.feed, "feed", "f", "", "trigger feed")

    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.annotation, "annotation", "a", []string{}, "annotations")
    triggerUpdateCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")
    triggerUpdateCmd.Flags().StringVar(&flags.common.shared, "shared", "", "shared action (yes = shared, no[default] = private)")

    triggerFireCmd.Flags().StringSliceVarP(&flags.common.param, "param", "p", []string{}, "default parameters")

    triggerListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "skip this many entities from the head of the collection")
    triggerListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return this many entities from the collection")

    triggerCmd.AddCommand(
        triggerFireCmd,
        triggerCreateCmd,
        triggerUpdateCmd,
        triggerGetCmd,
        triggerDeleteCmd,
        triggerListCmd,
    )

}
