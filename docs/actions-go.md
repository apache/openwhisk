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

# Creating and Invoking Go Actions

The `action-golang-v1.15` runtime can execute actions written in the Go programming language in OpenWhisk, either as precompiled binary or compiling sources on the fly.

## Entry Point

The source code of an action is one or more Go source files. The entry point of the action is a function, placed in the `main` package. The default name for the main function is `Main`, but you can change it to any name you want using the `--main` switch in `wsk`. The name is however always capitalized. The function must have a specific signature, as described next.

*NOTE* The runtime does *not* support different packages from `main` for the entry point. If you specify `hello.main` the runtime will try to use `Hello.main`, that will be almost certainly incorrect. You can however have other packages in your sources, as described below.

## Signature

The expected signature for a `main` function is:

`func Main(event map[string]interface{}) map[string]interface{}`

So a very simple single file `hello.go` action would be:

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

You can deploy it with just:

```
wsk action create hello-go hello.go
```

You can also have multiple source files in an action, packages and vendor folders.

## Deployment

The runtime `action-golang-v1.15` accepts:

- executable binaries in Linux ELF executable compiled for the AMD64 architecture
- zip files containing a binary executable named `exec` at the top level, again a Linux ELF executable compiled for the AMD64 architecture
- a single source file in Go, that will be compiled
- a zip file not containing in the top level a binary file `exec`, it will be interpreted as a collection of source files in Go, and compiled

You can create a binary in the correct format on any Go platform cross-compiling with `GOOS=Linux` and `GOARCH=amd64`. However it is recommended you use the compiler embedded in the Docker image for this purpose using the precompilation feature, as described below.

## Using packages and vendor folder

When you deploy a zip file, you can:

- have all your functions in the `main` package
- have some functions placed in some packages, like `hello`
- have some third party dependencies you want to include in your sources

If all your functions are in the main package, just place all your sources in the top level of your zip file.

### Use a package folder

If some functions belongs to a package, like `hello/`, you need to be careful with the layout of your sources, especially if you use editors like [VcCode](#vscode), and make. The layout recommended is the following:

```
golang-main-package/
- Makefile
- src/
   - main.go
   - main_test.go
   - hello/
       - hello.go
       - hello_test.go
```

For running tests, editing without errors with package resolution, you need to use a `src` folder, place the sources that belongs to the main package in the `src` and place sources of your package in the `src/hello` folder.

You should import it your subpackage with `import "hello"`.
Note this means if you want to compile locally you have to set your `GOPATH` to parent of your `src` directory. If you use VSCode, you need to enable the `go.inferGopath` option.

When you send the sources, you will have to zip the content of the `src` folder, *not* the main directory. For example:

```
cd src
zip -r ../hello.zip *
cd ..
wsk action create hellozip hello.zip --kind go:1.11
```

Check the example [golang-main-package](https://github.com/apache/openwhisk-runtime-go/tree/master/examples/golang-main-package) and the associated `Makefile`.

### Using vendor folders

When you need to use third party libraries, the runtime does not download them from Internet when compiling. You have to provide them,  downloading and placing them using the `vendor` folder mechanism. We are going to show here how to use the vendor folder with the `dep` tool.

*NOTE* the `vendor` folder does not work at the top level, you have to use a `src` folder and a package folder to have also the `vendor` folder. If you want use the vendor folder for the `main` package, you can do it but instead of placing files that belongs to the `main` package in the top-level, you have to place in a subfolder named `main`.

For example consider you have in the file `src/hello/hello.go` the import:

```
import "github.com/sirupsen/logrus"
```

To create a vendor folder, you need to

- install the [dep](https://github.com/golang/dep) tool
- cd to the `src/hello` folder (*not* the `src` folder)
- run `DEPPROJECTROOT=$(realpath $PWD/../..) dep init` the first time

The tool will detect the used libraries and create 2 manifest files `Gopkg.lock` and `Gopkg.toml`. If already have the manifest files, you just need `dep ensure` to create and populate the `vendor` folder.

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

Check the example [golang-hello-vendor](https://github.com/apache/openwhisk-runtime-go/tree/master/examples/golang-hello-vendor)

Note you do not need to store the `vendor` folder in the version control system as it can be regenerated, you only the manifest files. However, you need to include the entire vendor folder when you deploy the action in source format for compilation by the runtime.

If you need to use vendor folder in the main package, you need to create a directory `main` and place all the source code that would normally go in the top level, in the `main` folder instead.  A vendor folder in the top level *does not work*.


<a name="precompile"/>

## Precompiling Go Sources Offline

Compiling sources on the image can take some time when the images is initialized. You can speed up precompiling the sources using the image `action-golang-v1.15` as an offline compiler. You need `docker` for doing that.

The images accepts a `-compile <main>` flag, and expects you provide sources in standard input. It will then compile them, emit the binary in standard output and errors in stderr. The output is always a zip file containing an executable.

If you have a single source maybe in file `main.go`, with a function named `Main` just do this:

`docker run openwhisk/action-golang-v1.15 -compile main <main.go >main.zip`

If you have multiple sources in current directory, even with a subfolder with sources, you can compile it all with:

```
cd src
zip -r ../src.zip *
cd ..
docker -i run openwhisk/action-golang-v1.15 -compile main <src.zip >exec.zip
```

Note that the output is always a zip file in  Linux AMD64 format so the executable can be run only inside a Docker Linux container.

Here a `Makefile` is helpful. Check the [examples](https://github.com/apache/openwhisk-runtime-go/tree/master/examples) for a collection of tested Makefiles. The  generated executable is suitable to be deployed in OpenWhisk, so you can do:

`wsk action create my-action exec.zip --kind go:1.15`

You can also use just the `openwhisk/actionloop` as runtime, it is smaller.

<a name="vscode">

## Using VsCode

If you are using [VsCode[(https://code.visualstudio.com/) as your Go development environment with the [VsCode Go](https://marketplace.visualstudio.com/items?itemName=ms-vscode.Go) support, without errors and with completion working you need to:

- enable the option `go.inferGopath`
- place all your sources in a `src` folder
- either to open the `src` folder as the top level source or add it as a folder in the workspace (it is not enough just have it as a subfolder)
- create a `dummy.go` an empty main - it will not be used but it will shut up "`main.main` missing error detection"
