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

# Implementing feeds

OpenWhisk supports an open API, where any user can expose an event producer service as a **feed** in a **package**.   This section describes architectural and implementation options for providing your own feed.

This material is intended for advanced OpenWhisk users who intend to publish their own feeds.  Most OpenWhisk users can safely skip this section.

# Feed Architecture

There are at least 3 architectural patterns for creating a feed: **Hooks**, **Polling** and **Connections**.

## Hooks
In the *Hooks* pattern, we set up a feed using a [webhook](https://en.wikipedia.org/wiki/Webhook) facility exposed by another service.   In this strategy, we configure a webhook on an external service to POST directly to a URL to fire a trigger.  This is by far the easiest and most attractive option for implementing low-frequency feeds.

<!-- The github feed is implemented using webhooks.  Put a link here when we have the open repo ready -->

## Polling
In the "Polling" pattern, we arrange for an OpenWhisk *action* to poll an endpoint periodically to fetch new data. This pattern is relatively easy to build, but the frequency of events will of course be limited by the polling interval.

## Connections
In the "Connections"  pattern, we stand up a separate service somewhere that maintains a persistent connection to a feed source.    The connection based implementation might interact with a service endpoint via long polling, or to set up a push notification.

<!-- Our cloudant changes feed is connection based.  Put a link here to
an open repo -->

<!-- What is the foundation for the Message Hub feed? If it is "connections" then lets put a link here as well -->

# Difference between Feed and Trigger

Feeds and triggers are closely related,
but technically distinct concepts.

- OpenWhisk processes **events** which flow into the system.

- A **trigger** is technically a name for a class of events.   Each event belongs to exactly one trigger; by analogy, a trigger resembles a *topic* in topic-based pub-sub systems. A **rule** *T -> A* means "whenever an event from trigger *T* arrives, invoke action *A* with the trigger payload.

- A **feed** is a stream of events which all belong to some trigger *T*. A feed is controlled by a **feed action** which handles creating, deleting, pausing, and resuming the stream of events which comprise a feed. The feed action typically interacts with external services which produce the events, via a REST API that manages notifications.

#  Implementing Feed Actions

The *feed action* is a normal OpenWhisk *action*, but it should accept the following parameters:
* **lifecycleEvent**: one of 'CREATE', 'READ', 'UPDATE', 'DELETE', 'PAUSE', or 'UNPAUSE'.
* **triggerName**: the fully-qualified name of the trigger which contains events produced from this feed.
* **authKey**: the Basic auth credentials of the OpenWhisk user who owns the trigger just mentioned.

The feed action can also accept any other parameters it needs to manage the feed.  For example the cloudant changes feed action expects to receive parameters including *'dbname'*, *'username'*, etc.

When the user creates a trigger from the CLI with the **--feed** parameter, the system automatically invokes the feed action with the appropriate parameters.

For example,assume the user has created a `mycloudant` binding for the `cloudant` package with their username and password as bound parameters. When the user issues the following command from the CLI:

`wsk trigger create T --feed mycloudant/changes -p dbName myTable`

then under the covers the system will do something equivalent to:

`wsk action invoke mycloudant/changes -p lifecycleEvent CREATE -p triggerName T -p authKey <userAuthKey> -p password <password value from mycloudant binding> -p username <username value from mycloudant binding> -p dbName mytype`

The feed action named *changes* takes these parameters, and is expected to take whatever action is necessary to set up a stream of events from Cloudant, with the appropriate configuration, directed to the trigger *T*.

For the Cloudant *changes* feed, the action happens to talk directly to a *cloudant trigger* service we've implemented with a connection-based architecture.   We'll discuss the other architectures below.

A similar feed action protocol occurs for `wsk trigger delete`, `wsk trigger update` and `wsk trigger get`.

# Implementing Feeds with Hooks

It is easy to set up a feed via a hook if the event producer supports a webhook/callback facility.

With this method there is _no need_ to stand up any persistent service outside of OpenWhisk.  All feed management happens naturally though stateless OpenWhisk *feed actions*, which negotiate directly with a third part webhook API.

When invoked with `CREATE`, the feed action simply installs a webhook for some other service, asking the remote service to POST notifications to the appropriate `fireTrigger` URL in OpenWhisk.

The webhook should be directed to send notifications to a URL such as:

`POST /namespaces/{namespace}/triggers/{triggerName}`

The form with the POST request will be interpreted as a JSON document defining parameters on the trigger event. OpenWhisk rules pass these trigger parameters to any actions to fire as a result of the event.

# Implementing Feeds with Polling

It is possible to set up an OpenWhisk *action* to poll a feed source entirely within OpenWhisk, without the need to stand up any persistent connections or external service.

For feeds where a webhook is not available, but do not need high-volume or low latency response times, polling is an attractive option.

To set up a polling-based feed, the feed action takes the following steps when called for `CREATE`:

1.   The feed action sets up a periodic trigger (*T*) with the desired frequency, using the `whisk.system/alarms` feed.
2.   The feed developer creates a `pollMyService` action which simply polls the remote service and returns any new events.
3.  The feed action sets up a *rule* *T -> pollMyService*.

This procedure implements a polling-based trigger entirely using OpenWhisk actions, without any need for a separate service.

# Implementing Feeds via Connections

The previous 2 architectural choices are simple and easy to implement. However, if you want a high-performance feed, there is no substitute for persistent connections and long-polling or similar techniques.

Since OpenWhisk actions must be short-running,  an action cannot maintain a persistent connection to a third party . Instead, we must
stand up a separate service (outside of OpenWhisk) that runs all the time.   We call these *provider services*.  A provider service can maintain connections to third party event sources that support long polling or other connection-based notifications.

The provider service should provide a REST API that allows the OpenWhisk *feed action* to control the feed.   The provider service acts as a proxy between the event provider and OpenWhisk -- when it receives events from the third party, it sends them on to OpenWhisk by firing a trigger.

The Cloudant *changes* feed is the canonical example -- it stands up a `cloudanttrigger` service which mediates between Cloudant notifications over a persistent connection, and OpenWhisk triggers.
<!-- TODO: add a reference to the open source implementation -->

The *alarm* feed is implemented with a similar pattern.

The connection-based architecture is the highest performance option, but imposes more overhead on operations compared to the polling and hook architectures.

