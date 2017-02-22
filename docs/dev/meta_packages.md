# Meta Packages

Meta packages, like web actions, defer the cost of an action activation from the caller to the asset owner. Unlike web actions which are not authenticated, actions in a meta package may only be invoked by an authenticated OpenWhisk subject.

We have restricted meta packages to a single designated _system_ namespace, although this may change in the future. The API gateway [integration is implemented entirely as a meta package](../ansible/roles/routemgmt/files/installRouteMgmt.sh). A meta package defines a mapping from HTTP verbs (e.g., POST, GET, DELETE, PUT) to actions in the package that should handle each of these verbs.

An action in a meta package, when invoked, receives all of the information that a web action receives, in addition to the following property that identifies the authenticated subject:
1. `__ow_meta_namespace`: the namespace of the authenticated OpenWhisk user naming the request

A meta action is accessed via the URI `/api/v1/experimental/package-name/action-name[/unmatched/parts/passed/to/action]`. The controller will determine if the named package exists and carries the `meta` annotation and has a proper mapping from the HTTP method to an action. If so, it invokes the action on behalf of the caller but defers the costs of the activation to the meta package owner.

While any package may carry this annotation, the experimental feature gated by this annotation is currently restricted to a single designated _system_ namespace.
