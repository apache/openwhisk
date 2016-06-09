To build the wsk CLI run ant buildGoCLI from this directory.

$ cd tools/go-cli
$ ant buildGoCLI

The build will produce the cli in ../bin/go-cli/$platform/$architecture/wsk

The $platform is linux, mac, or windows.

The $architecture is 386, amd64, arm, arm64, etc.

To get the help run wsk --help

The WSK_CLI_DEBUG can to be used set to "true" to provide debug information.

