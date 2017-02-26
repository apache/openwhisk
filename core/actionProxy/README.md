Skeleton for "docker actions"
================

The `dockerskeleton` base image is useful for actions that run scripts (e.g., bash, perl, python)
and compiled binaries or, more generally, any native executable. It provides a proxy service
(using Flask, a Python web microframework) that implements the required `/init` and `/run` routes
to interact with the OpenWhisk invoker service. The implementation of these routes is encapsulated
in a class named `ActionRunner` which provides a basic framework for receiving code from an invoker,
preparing it for execution, and then running the code when required.

The initialization of the `ActionRunner` is done via `init()` which receives a JSON object containing
a `code` property whose value is the source code to execute. It writes the source to a `source` file.
This method also provides a hook to optionally augment the received code via an `epilogue()` method,
and then performs a `build()` to generate an executable. The last step of the initialization applies
`verify()` to confirm the executable has the proper permissions to run the code. The action runner
is ready to run the action if `verify()` is true.

The default implementations of `epilogue()` and `build()` are no-ops and should be overridden as needed.
The base image contains a stub added which is already executable by construction via `docker build`.
For language runtimes (e.g., C) that require compiling the source, the extending class should run the
required source compiler during `build()`.

The `run()` method runs the action via the executable generated during `init()`. This method is only called
by the proxy service if `verify()` is true. `ActionRunner` subclasses are encouraged to override this method
if they have additional logic that should cause `run()` to never execute. The `run()` method calls the executable
via a process and sends the received input parameters (from the invoker) to the action via the command line
(as a JSON string argument). Additional properties received from the invoker are passed on to the action via
environment variables as well. To augment the action environment, override `env()`.

By convention the action executable may log messages to `stdout` and `stderr`. The proxy requires that the last
line of output to `stdout` is a valid JSON object serialized to string if the action returns a JSON result.
A return value is optional but must be a JSON object (properly serialized) if present.

For an example implementation of an `ActionRunner` that overrides `epilogue()` and `build()` see the
[Swift 3](../swift3Action/swift3runner.py) action proxy. An implementation of the runner for Python actions
is available [here](../pythonAction/pythonrunner.py). Lastly, an example Docker action that uses `C` is
available in this [example](../../sdk/docker/Dockerfile).
