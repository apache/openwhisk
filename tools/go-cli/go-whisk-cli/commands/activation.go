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

    //"github.com/fatih/color"
    "github.com/spf13/cobra"
)

const (
    PollInterval = time.Second * 2
    Delay        = time.Second * 5
)

// activationCmd represents the activation command
var activationCmd = &cobra.Command{
    Use:   "activation",
    Short: "work with activations",
}

var activationListCmd = &cobra.Command{
    Use:   "list <namespace string>",
    Short: "list activations",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var err error
        qName := qualifiedName{}

        // Specifying an activation item name filter is optional
        if len(args) == 1 {
            whisk.Debug(whisk.DbgInfo, "Activation item name filter '%s' provided\n", args[0])
            qName, err = parseQualifiedName(args[0])
            if err != nil {
                whisk.Debug(whisk.DbgError, "parseQualifiedName(%s) failed: %s\n", args[0], err)
                errStr := fmt.Sprintf("'%s' is not a valid qualified name: %s", args[0], err)
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            ns := qName.namespace
            if len(ns) == 0 {
                whisk.Debug(whisk.DbgError, "Namespace '%s' is invalid\n", ns)
                errStr := fmt.Sprintf("Namespace '%s' is invalid", ns)
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
                return werr
            }
            client.Namespace = ns

            if pkg := qName.packageName; len(pkg) > 0 {
                // todo :: scope call to package
            }
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
            errStr := fmt.Sprintf("Unable to obtain list of activations: %s", err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
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
    Use:   "get <id string>",
    Short: "get activation",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }
        id := args[0]
        activation, _, err := client.Activations.Get(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.Get(%s) failed: %s\n", id, err)
            errStr := fmt.Sprintf("Unable to obtain activation record for '%s': %s", id, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        if flags.common.summary {
            fmt.Printf("activation result for /%s/%s (%s at %s)\n", activation.Namespace, activation.Name, activation.Response.Status, time.Unix(activation.End/1000, 0))
            printJsonNoColor(activation.Response.Result)
        } else {
            //fmt.Printf("%s got activation %s\n", color.GreenString("ok:"), boldString(id))  - MWD Does not work on Windows
            fmt.Printf("ok: got activation %s\n", id)
            printJsonNoColor(activation)
        }

        return nil
    },
}

var activationLogsCmd = &cobra.Command{
    Use:   "logs",
    Short: "get the logs of an activation",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        id := args[0]
        activation, _, err := client.Activations.Logs(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.Logs(%s) failed: %s\n", id, err)
            errStr := fmt.Sprintf("Unable to obtain logs for activation '%s': %s", id, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        printActivationLogs(activation.Logs)
        return nil
    },
}

var activationResultCmd = &cobra.Command{
    Use:   "result",
    Short: "get the result of an activation",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        if len(args) != 1 {
            whisk.Debug(whisk.DbgError, "Invalid number of arguments: %d\n", len(args))
            errStr := fmt.Sprintf("Invalid number of arguments (%d) provided; exactly one argument is expected", len(args))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            return werr
        }

        id := args[0]
        result, _, err := client.Activations.Result(id)
        if err != nil {
            whisk.Debug(whisk.DbgError, "client.Activations.result(%s) failed: %s\n", id, err)
            errStr := fmt.Sprintf("Unable to obtain result information for activation '%s': %s", id, err)
            werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        //fmt.Printf("%s got activation %s result\n", color.GreenString("ok:"), boldString(id))  - MWD Does not work on Windows
        //fmt.Printf("ok: got activation %s result\n", id)
        printJsonNoColor(result.Result)
        return nil
    },
}

var activationPollCmd = &cobra.Command{
    Use:   "poll <namespace string>",
    Short: "poll continuously for log messages from currently running actions",
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var name string
        var pollSince int64 // Represents an instant in time (in milliseconds since Jan 1 1970)

        // The activation item filter is optional
        if len(args) == 1 {
            name = args[0]
        }

        c := make(chan os.Signal, 1)
        signal.Notify(c, os.Interrupt)
        signal.Notify(c, syscall.SIGTERM)
        go func() {
            <-c
            fmt.Println("Poll terminated")
            os.Exit(1)
        }()
        fmt.Println("Enter Ctrl-c to exit.")

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

        fmt.Printf("Polling for activation logs\n")
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
                    fmt.Printf("\nActivation: %s (%s)\n", activation.Name, activation.ActivationID)
                    //MWD printJSON(activation.Logs)
                    printJsonNoColor(activation.Logs)
                    reported[activation.ActivationID] = true
                }
            }
            time.Sleep(time.Second * 2)
        }
        return nil
    },
}

func init() {

    activationListCmd.Flags().IntVarP(&flags.common.skip, "skip", "s", 0, "skip this many entitites from the head of the collection")
    activationListCmd.Flags().IntVarP(&flags.common.limit, "limit", "l", 30, "only return this many entities from the collection")
    activationListCmd.Flags().BoolVarP(&flags.common.full, "full", "f", false, "include full entity description")
    activationListCmd.Flags().Int64Var(&flags.activation.upto, "upto", 0, "return activations with timestamps earlier than UPTO; measured in miliseconds since Th, 01, Jan 1970")
    activationListCmd.Flags().Int64Var(&flags.activation.since, "since", 0, "return activations with timestamps earlier than UPTO; measured in miliseconds since Th, 01, Jan 1970")

    activationGetCmd.Flags().BoolVarP(&flags.common.summary, "summary", "s", false, "summarize entity details")

    activationPollCmd.Flags().IntVarP(&flags.activation.exit, "exit", "e", 0, "exit after this many seconds")
    activationPollCmd.Flags().IntVar(&flags.activation.sinceSeconds, "since-seconds", 0, "start polling for activations this many seconds ago")
    activationPollCmd.Flags().IntVar(&flags.activation.sinceMinutes, "since-minutes", 0, "start polling for activations this many minutes ago")
    activationPollCmd.Flags().IntVar(&flags.activation.sinceHours, "since-hours", 0, "start polling for activations this many hours ago")
    activationPollCmd.Flags().IntVar(&flags.activation.sinceDays, "since-days", 0, "start polling for activations this many days ago")

    activationCmd.AddCommand(
        activationListCmd,
        activationGetCmd,
        activationLogsCmd,
        activationResultCmd,
        activationPollCmd,
    )
}
