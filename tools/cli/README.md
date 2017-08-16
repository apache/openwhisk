To build the OpenWhisk CLI run the following command from the OpenWhisk home directory:

    $ ./gradlew :tools:cli:distDocker

Multiple binaries are produced in a Docker container during the build process. One of those binaries is copied from the
Docker container to the local file system in the following directory: ../../bin/wsk. This binary will be platform
specific, it will only run on the operating system, and CPU architecture that matches the build machine.

Currently the build process is only supported on Linux, and Mac operating systems running on an x86 CPU architecture.

To get CLI command help, execute the following command:

$ wsk --help

To get CLI command debug information, include the -d, or --debug flag when executing a command.


## Cross platform compilation

To build binaries for all the platforms (Linux, macOS and Windows) use: 

    $ ./gradlew :tools:cli:distDocker -PcrossCompileCLI=true

The binaries can then be found in `$OPENWHISK_HOME/bin`, in the `linux`, `mac` & `windows` subdirectories as appropriate.
