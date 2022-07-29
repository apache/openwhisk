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

# Title
User Defined Action Level Concurrency Limits Within Confines of Global Namespace Limit

## Status
* Current state: In-progress
* Author(s): @bdoyle0182 (Github ID)

## Summary and Motivation

Currently, openwhisk has a single concurrency limit for managing auto scaling within a namespace. This limit for each namespace is managed
rightly by system administrators to maintain a good balance between the namespaces of the system and the total system's resources.

However, this does not allow for the user to control how their applications scale within the namespace that they are operating. There is no
fairness across functions within a namespace. The semantics of a namespace can vary heavily depending on how openwhisk is being used. A namespace
could represent an organization for public cloud, a group within an organization, an application of functions, a logical grouping of applications
(for example putting all of your interactions with slack in one namespace).

The problem is that a single function can runaway and end up using all of the namespace's resources. It shouldn't be on the system administrators
to provide this fairness as it's dependent on the application and what the user wants. They may want the existing behavior to allow any action
to scale up to the total namespace's resources, they may want to restrict one less prioritized function scale up to a smaller threshold so it can't eat
the entire namespace's resources but still allow other high priority functions access to the entire namespace's resources, or they may want to provide
limits to all of their actions that add up to their namespace limit which will guarantee each action in their namespace can have up to their defined
action concurrency limits similar to other FaaS providers concept of reserved concurrency for actions.

With the major revision to how Openwhisk processes activations with the new scheduler, such a feature becomes extremely easy to implement by just adding
a single new limit that users can configure on their action document.

## Proposed changes: Architecture Diagram (optional), and Design

Add a optional `maxContainerConcurrency` limit field to action documents in the limits section. This limit will be used in the scheduler when deciding
if there is capacity for the action to scale up more containers. Previously, the scheduler was completely naive of functions across a namespace when provisioning
more containers, but if this limit is defined the scheduler will only allow to provision containers up to the defined action limit (which must be less than or equal to the namespace limit).

### Implementation details

A working PR of this POEM is already done in which implementation details can be reviewed but I will describe implementation considerations here. Once the POEM is approved,
I will add any feedback from the POEM, tests, and documentation.

- The scheduler decision maker uses the min of action container concurrency limit and the namespace concurrency limit. If the action limit is less than the namespace
limit, it will check both that the action hasn't used up all its capacity and that the namespace still has capacity if the action does still have capacity.
- The new limit `maxContainerConcurrency` on the action document is an optional field. If the field does not exist, the action limit used by the system is
the namespace limit making this an optional feature.
- The one thing not yet included in the implementation param is a parameter on the create action api which will allow the user to delete the limit field so that
the action will rely on the namespace limit again.
- When creating an action, the api will validate that your action container concurrency limit is less than or equal to the namespace concurrency limit. If it is greater,
the upload will fail with a BadRequest and error message that the limit must be less than the namespace limit with the namespace limit value included in the message.
- If the system admin lowers a namespace's concurrency limit below an amount that an existing action document has already configured, it will not break the action.
Since the scheduler just decides what the limit is to use to determine capacity based on the min of the namespace and action limit, it will therefore just use
the namespace limit as the capacity limit. Therefore, there is no action required or side effects or coordination required from the system admin wanting to lower the namespace limit.
However, if the user wants to redeploy the same function with the same limit that is now over the namespace limit; the api will now reject the action upload until the action limit
is lowered below the new namespace limit.
- A user may want to update their action to go back to just relying on the namespace limit. Since updates to action documents copy over limits in the update even if not
supplied on the request object, a boolean param will need to be added to the create action api so that the field is not copied in the update. **This is the one thing I still
need to add in the src code of the implementation PR.**
- In the scheduler, if the action limit is hit and new containers cannot be provisioned for the action but there is still capacity available for the namespace, namespace throttling
will not be turned on. The action queue will rely on action throttling if the queue grows too large if this case is hit. Namespace throttling will still be turned on if
the total containers hits the namespace limit.

## Integration and Migration plan (optional)

The feature is fully backwards compatible with existing action documents since the new limit is an optional field. If the limit is not defined on an action document,
the existing behavior is used where the action can have up to the namespace concurrency limit so there is no change to behavior if the feature is not used.
If using the old scheduler and the limit is defined on the action document, the limit just won't do anything until migrated to the new scheduler.
