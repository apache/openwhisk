# Title
Proposal for OpenWhisk EnhanceMents (POEMs)

## Status
* Current state: Draft
* Author: @style95

## Summary

A Proposal for OpenWhisk EnhanceMent(POEM) is a way to propose and manage new features/ideas in OpenWhisk.
The one who wants to include any major new feature, subsystem, piece of functionality and any breaking changes is supposed to open a proposal under this directory.
 
## Motivation

As the project grows, more and more issues are getting complex and require multiple parties and an extended period of time to develop them.
We can incubate, manage, and elaborate new ideas in OpenWhisk with a standard way and a well-tracked artifact.
It would enhance the discoverability of proposals and help community members to involve in the project better.

## Proposed changes

### Procedures
1. Create a pull request to describe your proposal with [this template](./POEM-N-template.md). The initial state of a proposal should be _"Draft"_.
2. [Create a corresponding issue]((/issues/new?template=proposal.md)) to propose a new change based on [this template](../github/ISSUE_TEMPLATE/proposal.md). It is mainly used to track discussion history.
3. Discuss the proposals with any channels(issue comments, mailing list, Slack, etc) to form a consensus and update your proposal based on comments as much as needed.
4. When members form a consensus for the proposal. The PR can be merged with the _"In-progress"_ state. And the implementation begins and any volunteer can join the implementation ideally.
5. If the proposal is not accepted and no consensus is formed, the PR still can be merged with the state, _"Abandoned"_.
6. When implementation is included in the master branch, it is supposed to update the state of the proposal to _"Completed"_ as well. A corresponding issue is supposed to be closed at this point.

Note: Committers and PMCs are supposed to label issues with an appropriate label to track its status.


### Proposal Lifecycle
A proposal is supposed to be in one of the following states: 
* **Draft**: A new enhancement is proposed and it is under discussion.
* **In-progress**: A consensus for the proposal is formed and implementation is in progress.
* **Complete**: Implementation is finished and the change is included in the master branch.
* **Abandoned**: A proposal is not accepted for some reason such as ”no consensus is formed”.


## Issue

Abandoned proposals would also be filed in this directory for history.
This is to keep and track all proposals at any stage in one place.
A new idea can be derived from the abandoned one. 

