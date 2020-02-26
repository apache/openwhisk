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

# How to submit a Proposal for an OpenWhisk Enhancement (POEM)

This directory contains Proposals for OpenWhisk Enhancements (POEM). Proposed changes, new features and ideas are documented using a process outlined in this document. If you're a contributor interested in out of the proposals, you are encouraged to reach out to the proposal authors. This is a welcoming community and we look forward to your contributions.
This directory contains approved proposals. The one who wants to include any major new feature, subsystem, piece of functionality and any breaking changes is supposed to open a proposal.

You can find the full details in [POEM-1](./POEM-1-proposal-for-openwhisk-enhancements.md).

## Quickstart to propose a new change.
1. Copy the [POEM template](./POEM-N-template.md) and open a pull request.
2. [Create a corresponding issue](https://github.com/apache/openwhisk/issues/new?template=proposal.md) with the prefix, `[Proposal]` in the title.
3. Follow the process outlined in the [POEM-1](./POEM-1-proposal-for-openwhisk-enhancements.md).

## When should I open a proposal?
Create an issue when you propose:
 * any major new feature, subsystem, or piece of functionality
 * a big change which requires multiple PRs to complete it
 * a breaking change that impacts public interfaces, data structures, or core execution paths.

If you are not sure, ask to committers or the [PMC] of OpenWhisk.

The POEM process was inspired by KEP( Kubernetes Enhancement Proposals), KIP (Kafka Improvement Proposal), and Cordova-discuss.
