<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->
# :electric_plug: owperf - a performance test tool for Apache OpenWhisk

## General Info
This test tool benchmarks an OpenWhisk deployment for (warm) latency and throughput, with several new capabilities:
1. Measure performance of rules (trigger-to-action) in addition to actions
1. Deeper profiling without instrumentation (e.g., Kamino) by leveraging the activation records in addition to the client's timing data. This avoids special setups, and can help gain performance insights on third-party deployments of OpenWhisk.
1. New tunables that can affect performance:
   1. Parameter size - controls the size of the parameter passed to the action or event
   1. Actions per iteration (a.k.a. _ratio_) - controls how many rules are associated with a trigger [for rules] or how many actions are asynchronously invoked (burst size) at each iteration of a test worker [for actions].
1. "Master apart" mode - Allow the master client to perform latency measurements while the worker clients stress OpenWhisk using a specific invocation pattern in the background. Useful for measuring latency under load, and for comparing latencies of rules and actions under load.
The tool is written in node.js, using mainly the modules of OpenWhisk client, cluster for concurrency, and commander for CLI procssing.

### Operation
The general operation of a test is simple:
1. **Setup**: the tool creates the test action, test trigger, and a number of rules that matches the ratio tunable above.
1. **Test**: the tool fires up a specified number of concurrent clients - a master and workers.
   1. Each client wakes up once every _delta_ msec (iteration) and invokes the specified activity: either the trigger (for rule testing) or multiple concurrent actions - matching the ratio tunable. Action invocations can be blocking.
   1. After each client has completed a number of initial iterations (warmup), measurement begins, controlled by the master client, for either a specified number of iterations or specified time.
   1. At the end of the measurement, each client retrieves the activation records of its triggers and/or actions, and generates summary data that is sent to the master, which generates and prints the final results.
1. **Teardown**: clean up the OpenWhisk assets created during setup

Final results are written to the standard output stream (so can be redirected to a file) as a single highly-detailed CSV record containing all the input settings and the output measurements (see below). There is additional control information that is written to the standard error stream and can be silenced in CLI. The control information also contains the CSV header, so it can be copied into a spreadsheet if needed.

It is possible to invoke the tool in "Master apart" mode, where the master client is invoking a different activity than the workers, and at possibly a different (very likely, much slower) rate. In this mode, latency statsitics are computed based solely on the master's data, since the worker's activity is used only as background to stress the OpenWhisk deployment. So one experiment can have the master client invoke rules and another one can have the master client invoke actions, while in both experiments the worker clients perform the same background activity.

The tool is highly customizable via CLI options. All the independent test variables are controlled via CLI. This includes number of workers, invocation pattern, OW client configuration, test action sleep time, etc.

Test setup and teardown can be independently skipped via CLI, and/or directly invoked from the external setup script (```setup.sh```), so that setup can be shared between multiple tests. More advanced users can replace the test action with a custom action in the setup script to benchmark action invocation or event-respose throughput and latency of specific applications.

**Clock skew**: OpenWhisk is a distributed system, which means that clock skew is expected between the client machine computing invocation timestamps and the controllers or invokers that generate the timestamps in the activation records. However, this tool assumes that clock skew is bound at few msec range, due to having all machines clocks synchronized, typically using NTP. At such a scale, clock skew is quite small compared to the measured time periods. Some of the time periods are measured using the same clock (see below) and are therefore oblivious to clock skew issues.

## Initial Setup
The tool requires very little setup. You need to have node.js (v8+) and the wsk CLI client installed (on $PATH). Before the first run, execute ```npm install``` in the tool folder to install the dependencies.
**Throttling**: By default, OW performance is throttled according to some [limits](https://github.com/apache/openwhisk/blob/master/docs/reference.md#system-limits), such as maximum number of concurrent requests, or maximum invocations per minute. If your benchmark stresses OpenWhisk beyond the limit value, you might want to relax those limits. If it's an OpenWhisk deployment that you control, you can set the limits to 999999, thereby effectively cancelling the limits. If it's a third-party service, you may want to consult the service documentation and/or support to see what limits can be relaxed and by how much.

## Usage
To use the tool, run ```./owperf.sh <options>``` to perform a test. To see all the available options and defaults run ```./owperf.sh -h```.

The default for ratio is 1. If using a different ratio, be sure to specify the same ratio value for all steps.

For example, let's perform a test of rule performance with 3 clients, using the default delta of 200 msec, for 100 iterations (counted at the master client, excluding the warmup), ratio of 4. Each client performs 5 iterations per second, each iteration firing a trigger that invokes 4 rules, yielding a total of 3x5x4=60 rule invocations per second. The command to run this test: ```./owperf.sh -a rule -w 3 -i 100 -r 4```

## Measurements
As explained above, the owperf tool collects both latency and throughput data at each experiment.

### Latency
The following time-stamps are collected for each invocation, of either action, or rule (containing an action):
* **BI** (Before Invocation) - taken by a client immediately before invoking - either the trigger fire (for rules), or an action invocation.
* **TS** (Trigger Start) - taken from the activation record of the trigger linked to the rules, so applies only to rule tests. All actions invoked by the rules of the same trigger have the same TS value.
* **AS** (Action Start) - taken from the activation record of the action.
* **AE** (Action End) - taken from the activation record of the action.
* **AI** (After Invocation) - taken by the client immmediately after the invocation, for blocking action invocation tests only.

Based on these timestamps, the following measurements are taken:
* **OEA** (Overhead of Entering Action) - OpenWhisk processing overhead from sending the action invocation or trigger fire to the beginning of the action execution. OEA = AS-BI
* **D** - the duration of the test action - as reported by the action itself in the return value.
* **AD** - Action Duration - as measured by OpenWhisk invoker. AD = AE - AS. Always expect that AD >= D.
* **OER** (Overhead of Executing Request) - OpenWhisk processing overhead from sending the action invocation or trigger fire to the completion of the action execution in the OpenWhisk Invoker. OER = AE-BI-D
* **TA** (Trigger to Answer) - the processing time from the start of the trigger process to the start of the action (rule tests only). TA = AS-TS
* **ORA** (Overhead of Returning from Action) - time from action end till being received by the client (blocking action tests only). ORA = AI - AE
* **RTT** (Round Trip Time) - time at the client from action invocation till reply received (blocking action tests only). RTT = AI - BI
* **ORTT** (Overhead of RTT) - RTT at the client exclugin the net action computation time. ORTT = RTT - D

For each measurement, the tool computes average (_avg_), standard deviation (_std_), and extremes (_min_ and _max_).

The following chart depicts the relationship between the various measurements and the action invocation and rule invocation flows.

![](owperf_data.png)

### Throughput
Throughput is measured w.r.t. several different counters. During post-processing of an experiment, each counter value is divided by the measurement time period to compute a respective throughput.
* **Attempts** - number of invocation attempts performed inside the time frame (according to their BI). This is the "arrival rate" of invocations, should be close to _clients * ratio / delta_ .
* **Requests** - number of requests sent to OpenWhisk inside the time frame. Each action invocation is one request, and each trigger fire is also one request (so a client invoking rules at ratio _k_ generates _k+1_ requests).
* **Activations** - number of completed activations inside the time frame, counting both trigger activations (based on TS), and action activations (based on AS and AE).
* **Invocations** - number of successful invocations of complete rules or actions (depending on the activity). This is the "service rate" of invocations (assuming errors happen only because OW is overloaded).

For each counter, the tool reports the total counter value (_abs_), total throughput per second (_tp_), througput of the worker clients without the master (_tpw_) and the master's percentage of throughput relative to workers (_tpd_). The last two values are important mostly for master apart mode.

Aside from that, the tool also counts **errors**. Failed invocations - of actions, of triggers, or of actions from triggers (via rules) are counted each as an error. The tool reports both absolute error count (_abs_) and percent out of requests (_percent_).

## Acknowledgements
The owperf tool has been developed by IBM Research as part of the [CLASS](https://class-project.eu/) EU project. CLASS aims to integrate OpenWhisk as a foundation for latency-sensitive polyglot event-driven big-data analytics platform running on a compute continuum from the cloud to the edge. CLASS is funded by the European Union's Horizon 2020 Programme grant agreement No. 780622.

