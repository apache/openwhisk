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
    "os"
    "os/signal"
    "syscall"
    "time"

    "../../go-whisk/whisk"
    "../wski18n"

    "github.com/fatih/color"
    "github.com/spf13/cobra"
)

const (
    PollInterval = time.Second * 2
    Delay        = time.Second * 5
)

// activationCmd represents the activation command
var activationCmd = &cobra.Command{
    Use:   "activation",
    Short: wski18n.T("work with activations"),
}

var activationListCmd = &cobra.Command{
    Use:   "list [NAMESPACE or NAME]",
    Short: wski18n.T("list activations"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        qName := QualifiedName{}

        // Specifying an activation item name filter is optional
        if len(args) == 1 {
            whisk.Debug(whisk.DbgInfo, "Activation item name filter '%s' provided\n", args[0])
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errStr := wski18n.T("'{{.name}}' is not a valid qualified name: {{.err}}",
                        map[string]interface{}{"name": args[0], "err": err})
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace '%s' is invalid\n", ns)
                errStr := wski18n.T("Namespace '{{.name}}' is invalid", map[string]interface{}{"name": ns})
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }

            client.Namespace = ns
        } else if whiskErr := checkArgs(args, 0, 1, "Activation list",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        options := &whisk.ActivationListOptions{
            Name:  qName.entityName,
            Limit: flags.common.limit,
            Skip:  flags.common.skip,
            Upto:  flags.activation.upto,
            Since: flags.activation.since,
            Docs:  flags.common.full,
        }
        activations, _, err := client.Activations.List(options)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.List() error: %s\n", err)
            errStr := wski18n.T("Unable to obtain the list of activations for namespace '{{.name}}': {{.err}}",
                    map[string]interface{}{"name": getClientNamespace(), "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL,
                whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // When the --full (URL contains "?docs=true") option is specified, display the entire activation details
        if options.Docs == true {
            printFullActivationList(activations)
        } else {
            printList(activations)
        }

        return nil
    },
}

var activationGetCmd = &cobra.Command{
    Use:   "get ACTIVATION_ID [FIELD_FILTER]",
    Short: wski18n.T("get activation"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var field string

        if whiskErr := checkArgs(args, 1, 2, "Activation get",
                wski18n.T("An activation ID is required.")); whiskErr != nil {
            return whiskErr
        }

        if len(args) > 1 {
            field = args[1]

            if !fieldExists(&whisk.Activation{}, field) {
                errMsg := wski18n.T("Invalid field filter '{{.arg}}'.", map[string]interface{}{"arg": field})
                whiskErr := whisk.MakeWskError(errors.New(errMsg), whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return whiskErr
            }
        }

        id := args[0]
        activation, _, err := client.Activations.Get(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.Get(%s) failed: %s\n", id, err)
            errStr := wski18n.T("Unable to get activation '{{.id}}': {{.err}}",
                    map[string]interface{}{"id": id, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        if flags.common.summary {
            fmt.Printf(
                wski18n.T("activation result for /{{.namespace}}/{{.name}} ({{.status}} at {{.time}})\n",
                    map[string]interface{}{
                        "namespace": activation.Namespace,
                        "name": activation.Name,
                        "status": activation.Response.Status,
                        "time": time.Unix(activation.End/1000, 0)}))
            printJSON(activation.Response.Result)
        } else {

            if len(field) > 0 {
                fmt.Fprintf(color.Output,
                    wski18n.T("{{.ok}} got activation {{.id}}, displaying field {{.field}}\n",
                        map[string]interface{}{"ok": color.GreenString("ok:"), "id": boldString(id),
                        "field": boldString(field)}))
                printField(activation, field)
            } else {
                fmt.Fprintf(color.Output, wski18n.T("{{.ok}} got activation {{.id}}\n",
                        map[string]interface{}{"ok": color.GreenString("ok:"), "id": boldString(id)}))
                printJSON(activation)
            }
        }

        return nil
    },
}

var activationLogsCmd = &cobra.Command{
    Use:   "logs ACTIVATION_ID",
    Short: wski18n.T("get the logs of an activation"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        if whiskErr := checkArgs(args, 1, 1, "Activation logs",
                wski18n.T("An activation ID is required.")); whiskErr != nil {
            return whiskErr
        }

        id := args[0]
        activation, _, err := client.Activations.Logs(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.Logs(%s) failed: %s\n", id, err)
            errStr := wski18n.T("Unable to get logs for activation '{{.id}}': {{.err}}",
                map[string]interface{}{"id": id, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        printActivationLogs(activation.Logs)
        return nil
    },
}

var activationResultCmd = &cobra.Command{
    Use:   "result ACTIVATION_ID",
    Short: "get the result of an activation",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        if whiskErr := checkArgs(args, 1, 1, "Activation result",
                wski18n.T("An activation ID is required.")); whiskErr != nil {
            return whiskErr
        }

        id := args[0]
        result, _, err := client.Activations.Result(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.result(%s) failed: %s\n", id, err)
            errStr := wski18n.T("Unable to get result for activation '{{.id}}': {{.err}}",
                    map[string]interface{}{"id": id, "err": err})
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        printJSON(result.Result)
        return nil
    },
}

var activationPollCmd = &cobra.Command{
    Use:   "poll [NAMESPACE]",
    Short: wski18n.T("poll continuously for log messages from currently running actions"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var name string
        var pollSince int64 // Represents an instant in time (in milliseconds since Jan 1 1970)

        if len(args) == 1 {
            name = args[0]
        } else if whiskErr := checkArgs(args, 0, 1, "Activation poll",
                wski18n.T("An optional namespace is the only valid argument.")); whiskErr != nil {
            return whiskErr
        }

        c := make(chan os.Signal, 1)
        signal.Notify(c, os.Interrupt)
        signal.Notify(c, syscall.SIGTERM)
        go func() {
            <-c
            fmt.Println(wski18n.T("Poll terminated"))
            os.Exit(1)
        }()
        fmt.Println(wski18n.T("Enter Ctrl-c to exit."))

        // Map used to track activation records already displayed to the console
        reported := make(map[string]bool)

        if flags.activation.sinceSeconds+
        flags.activation.sinceMinutes+
        flags.activation.sinceHours+
        flags.activation.sinceDays ==
        0 {
            options := &whisk.ActivationListOptions{
                Limit: 1,
                Docs:  true,
            }
            activationList, _, err := client.Activations.List(options)
            if err != nil {
                whisk.Debug(whisk.DbgWarn, "client.Activations.List() error: %s\n", err)
                whisk.Debug(whisk.DbgWarn, "Ignoring client.Activations.List failure; polling for activations since 'now'\n")
                pollSince = time.Now().Unix() * 1000    // Convert to milliseconds
            } else {
                if len(activationList) > 0 {
                    lastActivation := activationList[0]     // Activation.Start is in milliseconds since Jan 1 1970
                    pollSince = lastActivation.Start + 1    // Use it's start time as the basis of the polling
                }
            }
        } else {
            pollSince = time.Now().Unix() * 1000    // Convert to milliseconds

            // ParseDuration takes a string like "2h45m15s"; create this duration string from the command arguments
            durationStr := fmt.Sprintf("%dh%dm%ds",
                flags.activation.sinceHours + flags.activation.sinceDays*24,
                flags.activation.sinceMinutes,
                flags.activation.sinceSeconds,
            )
            duration, err := time.ParseDuration(durationStr)
            if err == nil {
                pollSince = pollSince - duration.Nanoseconds()/1000/1000    // Convert to milliseconds
            } else {
                whisk.Debug(whisk.DbgError, "time.ParseDuration(%s) failure: %s\n", durationStr, err)
            }
        }

        fmt.Printf(wski18n.T("Polling for activation logs\n"))
        whisk.Verbose("Polling starts from %s\n", time.Unix(pollSince/1000, 0))
        localStartTime := time.Now()

        // Polling loop
        for {
            if flags.activation.exit > 0 {
                localDuration := time.Since(localStartTime)
                if int(localDuration.Seconds()) > flags.activation.exit {
                    whisk.Debug(whisk.DbgInfo, "Poll time (%d seconds) expired; polling loop stopped\n", flags.activation.exit)
                    return nil
                }
            }
            whisk.Verbose("Polling for activations since %s\n", time.Unix(pollSince/1000, 0))
            options := &whisk.ActivationListOptions{
                Name:  name,
                Since: pollSince,
                Docs:  true,
                Limit: 0,
                Skip: 0,
            }

            activations, _, err := client.Activations.List(options)
            if err != nil {
                whisk.Debug(whisk.DbgWarn, "client.Activations.List() error: %s\n", err)
                whisk.Debug(whisk.DbgWarn, "Ignoring client.Activations.List failure; continuing to poll for activations\n")
                continue
            }

            for _, activation := range activations {
                if reported[activation.ActivationID] == true {
                    continue
                } else {
                    fmt.Printf(
                        wski18n.T("\nActivation: {{.name}} ({{.id}})\n",
                            map[string]interface{}{"name": activation.Name, "id": activation.ActivationID}))
                    printJSON(activation.Logs)
                    reported[activation.ActivationID] = true
                }
            }
            time.Sleep(time.Second * 2)
        }
        return nil
    },
}

func init() {
    activationListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, wski18n.T("exclude the first `SKIP` number of activations from the result"))
    activationListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, wski18n.T("only return `LIMIT` number of activations from the collection"))
    activationListCmd.Flags().BoolVarP(&flags.common.full, "full", "f", false, wski18n.T("include full activation description"))
    activationListCmd.Flags().Int64Var(&flags.activation.upto, "upto", 0, wski18n.T("return activations with timestamps earlier than `UPTO`; measured in milliseconds since Th, 01, Jan 1970"))
    activationListCmd.Flags().Int64Var(&flags.activation.since, "since", 0, wski18n.T("return activations with timestamps later than `SINCE`; measured in milliseconds since Th, 01, Jan 1970"))

    activationGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, wski18n.T("summarize activation details"))

    activationPollCmd.Flags().IntVarP(&flags.activation.exit, "exit", "e", 0, wski18n.T("stop polling after `SECONDS` seconds"))
    activationPollCmd.Flags().IntVar(&flags.activation.sinceSeconds, "since-seconds", 0, wski18n.T("start polling for activations `SECONDS` seconds ago"))
    activationPollCmd.Flags().IntVar(&flags.activation.sinceMinutes, "since-minutes", 0, wski18n.T("start polling for activations `MINUTES` minutes ago"))
    activationPollCmd.Flags().IntVar(&flags.activation.sinceHours, "since-hours", 0, wski18n.T("start polling for activations `HOURS` hours ago"))
    activationPollCmd.Flags().IntVar(&flags.activation.sinceDays, "since-days", 0, wski18n.T("start polling for activations `DAYS` days ago"))

    activationCmd.AddCommand(
        activationListCmd,
        activationGetCmd,
        activationLogsCmd,
        activationResultCmd,
        activationPollCmd,
    )
}
