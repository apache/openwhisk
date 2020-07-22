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
Process for introducing an OpenWhisk Enhancement (POEM)

## Status
* Current state: Completed
* Author: @style95

## Summary

Introduce and document a process for suggesting and implementing a substantive OpenWhisk Enhancement (POEM).
A developer or group of developers working together to propose and implement a major new feature or functionality, a new subsystem, or a breaking change should follow the process described herein and open a proposal for consideration under this directory.

## Motivation

As the project grows, more and more issues are getting complex and require multiple parties and an extended period of time to develop them.
We can incubate, manage, and elaborate new ideas in OpenWhisk with a standard way and a well-tracked artifact.
The goals are to enhance the discoverability of proposals, and to help community members who want to get involved in the project.

## Proposed changes

### Procedures
1. Create a pull request to describe your proposal with [this template](./POEM-N-template.md). The initial state of a proposal should be _"Draft"_.
2. [Create a corresponding issue](https://github.com/apache/openwhisk/issues/new?template=proposal.md) to propose a new change based on [this template](../.github/ISSUE_TEMPLATE/proposal.md). It is mainly used to track discussion history.
3. Discuss the proposals using to form a consensus and update your proposal based on comments as needed. It is important to be inclusive, and to notify the OpenWhisk community of meaningful changes using the Apache [`dev` list](https://openwhisk.apache.org/community.html) for this project. Other forms of communication such as Slack are OK but any meaningful results should be documented in issues and the `dev` list.
4. When members form a rough consensus for the proposal. The proposal owner can request a vote via the dev mailing list.
5. The voting process follows the [Apache Voting guideline](https://www.apache.org/foundation/voting.html). The PR can be merged with the _"In-progress"_ state if the voting is successfully closed without any veto.
6. The implementation begins as the proposal is filed into the repo and any volunteer can join the implementation ideally.
7. If the proposal is not accepted or no consensus is formed, the PR is merged with the state, _"Abandoned"_.
8. The proposal state is changed to "Completed"_ and any corresponding issues are closed once the implementation is compete, and the code is merged into the master branch.

### Note
* Committers and the PMC are supposed to label issues with an appropriate label to track issue and pull request status.
* There are 4 labels(`draft`, `in-progress`, `completed`, and `abandoned`) to specify the state of a proposal and one special label (`proposal`) to differentiate proposals from other issues.

### Proposal Lifecycle
A proposal may be in one of the following states:
* **Draft**: A new enhancement is proposed and it is under discussion.
* **In-progress**: A consensus for the proposal is formed and implementation is in progress.
* **Completed**: Implementation is finished and the change is included in the master branch.
* **Abandoned**: A proposal is not accepted for some reason such as ”no consensus is formed”.

## Issue

Abandoned proposals are filed in this directory for archival.
This is to keep and track all proposals at any stage in one place.
A new idea can be derived from the abandoned one.
