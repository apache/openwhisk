To build the OpenWhisk CLI run the following command from the OpenWhisk home directory:

$ gradle :tools:go-cli:distDocker

Multiple binaries are produced in a Docker container during the build process. One of those binaries is copied from the
Docker container to the local file system in the following directory: ../../bin/go-cli/wsk. This binary will be platform
specific, it will only run on the operating system, and CPU architecture that matches the build machine.

Currently the build process is only supported on Linux, and Mac operating systems running on an x86 CPU architecture.

To get CLI command help, execute the following command:

$ wsk --help

To get CLI command debug information, include the -d, or --debug flag when executing a command.
