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

<a name="golang"/>
# How to write Go Actions

The `actionloop-golang-v1.11` runtime can execute actions written in the Go programming language in OpenWhisk, either precompiled binary or compiling sources on the fly.

### Entry Point

The source of one action is one or more Go source file. The entry point of the action is a function, placed in the `main` package. The obvious name for the default action would be `main`, but unfortunately `main.main` is the fixed entry point, for a go program, and its signature is `main()` (without arguments) so it cannot be used, unless you implement the [ActionLoop](#actionloopgo) directly, overwriting the one provided by the runtime, see below.

When deploying an OpenWhisk action you can specify the `main` function, and the default value is of course `main`.

The rule used by the runtime you use the  the *capitalized* name of the function specified as main. The default is of course `main.Main`' if you specify `hello` it will be `hello.Hello`. It will be also `main.Main` or `hello.Hello` if you specify the main function as, respectively, `Main` and `Hello`. The function must have a specific signature, as described next.

*NOTE* The runtime does *not* support different packages from `main` for the entry point. If you specify `hello.main` the runtime will try to use `Hello.main`, that will be almost certainly incorrect. You can however have other packages in your sources, as described below.

## Signature

The expected signature for a `main` function is:

`func Main(event map[string]interface{}) map[string]interface{}`

So a very simple `hello world` function would be:

```go
package main

import "log"

// Main is the function implementing the action
func Main(obj map[string]interface{}) map[string]interface{} {
  // do your work
  name, ok := obj["name"].(string)
  if !ok {
    name = "world"
  }
  msg := make(map[string]interface{})
  msg["message"] = "Hello, " + name + "!"
  // log in stdout or in stderr
  log.Printf("name=%s\n", name)
  // encode the result back in json
  return msg
}
```

You can also have multiple source files in an action, packages and vendor folders.

## Deployment

The runtime `actionloop-golang-v1.11` accepts:

- executable binaries implementing the ActionLoop protocol as Linux ELF executable compiled for the AMD64 architecture (as the `actionloop` runtme)
- zip files containing a binary executable named `exec` in the top level, and it must be again a Linux ELF executable compiled for the AMD64 architecture
- a single file action that is not an executable binary will be interpreted as source code and it will be compiled in a binary.
- a zip file not containing in the top level a binary file `exec` will  be interpreted as a collection of zip files, and it will be compiled as a binary.

Please note in the separate the rules about the name of the main function (that defaults to `main.Main`), and the rules about how to overwrite the `main.main`.

## Using packages and vendor folder

When you deploy a zip file, you can:

- have all your functions in the `main` package
- have some functions placed in some packages, like `hello`
- have some third party dependencies you want to include in your sources

If all your functions are in the main package, just place all your sources in the top level of your zip file

### Use a package folder

If some functions belong to a package, like `hello/`, you need to be careful with the layout of your source. The layout supported is the following:

```
golang-main-package/
-  Makefile
- src/
   - main.go
   - hello/
       -hello.go
       -hello_test.go
```

You need to use a `src` folder, place the sources that belongs to the main package in the `src` and place sources of your package in the `src/hello` folder.

Then you should import it your subpackage with `import "hello"`.
Note that this means if you want to compile locally you have to set your GOPATH to parent directory of your `src` packages. Check below for using [VcCode](#vscode) as an editor with this setup.

When you send the image you will have to zip the content

Check the example [golang-main-package](https://github.com/apache/incubator-openwhisk-runtime-go/tree/master/examples/golang-main-package) and the associated `Makefile` for an example including also how to deploy and precompile your sources.

### Using vendor folders

When you need to use third part libraries, the runtime does not download them from Internet. You have to provide them,  downloading and placing them using the `vendor` folder mechanism. We are going to show here how to use the vendor folder with the `dep` tool.

*NOTE* the `vendor` folder does not work at the top level, you have to use a `src` folder and a package folder to have also the vendor folder.

If you want for example use the library `github.com/sirupsen/logrus` to manage your logs (a widely used drop-in replacement for the standard `log` package), you have to include it in your source code *in a sub package*.

For example consider you have in the file `src/hello/hello.go` the import:

```
import "github.com/sirupsen/logrus"
```

To create a vendor folder, you need to

- install the [dep](https://github.com/golang/dep) tool
- cd to the `src/hello` folder (*not* the `src` folder)
- run `DEPPROJECTROOT=$(realpath $PWD/../..) dep init` the first time (it will create 2 manifest files `Gopkg.lock` and `Gopkg.toml`) or `dep ensure` if you already have the manifest files.

The layout will be something like this:

```
golang-hello-vendor
- Makefile
- src/
    - hello.go
    - hello/
      - Gopkg.lock
      - Gopkg.toml
          - hello.go
         - hello_test.go
         - vendor/
            - github.com/...
            - golang.org/... 
```

Check the example [golang-hello-vendor](https://github.com/apache/incubator-openwhisk-runtime-go/tree/master/examples/golang-hello-vendor)

Note you do not need to store the `vendor` folder in the version control system as it can be regenerated (only the manifest files), but you need to include the entire vendor folder when you deploy the action.

If you need to use vendor folder in the main package, you need to create a directory `main` and place all the source code that would normally go in the top level, in the `main` folder instead.  A vendor folder in the top level *does not work*.

<a name="vscode">

### Using VsCode

If you are using [VsCode[(https://code.visualstudio.com/) as your Go development environment with the [VsCode Go](https://marketplace.visualstudio.com/items?itemName=ms-vscode.Go) support, and you want to get rid of errors and have it working properly, you need to configure it to support the suggested:

- you need to have a `src` folder in your source
- you need either to open the `src` folder as the top level source or add it as a folder in the workspace (not just have it as a subfolder)
- you need to enable the option `go.inferGopath`

Using this option, the GOPATH will be set to the parent directory of your `src` folder and you will not have errors in your imports.

<a name="precompile"/>

## Precompiling Go Sources Offline

Compiling sources on the image can take some time when the images is initialized. You can speed up precompiling the sources using the image `actionloop-golang-v1.11` as an offline compiler. You need `docker` for doing that.

The images accepts a `-compile <main>` flag, and expects you provide sources in standard input. It will then compile them, emit the binary in standard output and errors in stderr. The output is always a zip file containing an executable.

If you have docker, you can do it this way:

If you have a single source maybe in file `main.go`, with a function named `Main` just do this:

`docker run openwhisk/actionloop-golang-v1.11 -compile main <main.go >main.zip`

If you have multiple sources in current directory, even with a subfolder with sources, you can compile it all with:

```
cd src
zip -r ../src.zip *
cd .. 
docker run openwhisk/actionloop-golang-v1.11 -compile main <src.zip >exec.zip
```

The  generated executable is suitable to be deployed in OpenWhisk

`wsk action create my/action exec.zip -docker openwhisk/actionloop-golang-v1.11`

You can also use just the `openwhisk/actionloop` as runtime, it is smaller.

Note that the output is always a zip file in  Linux AMD64 format so the executable can be run only inside a Docker Linux container.
