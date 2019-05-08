
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

# Developing a new Runtime with the ActionLoop proxy

The [runtime specification](actions-new.md) defines the expected behavior of a runtime. You can implement a runtime from scratch just following the specification.

However, the fastest way to develop a new runtime is reusing the *ActionLoop* proxy that already implements the specification and provides just a few hooks to get a fully functional (and *fast*) runtime in a few hours or less.

## What is the ActionLoop proxy

The ActionLoop proxy is a runtime "engine", written in the [Go programming language](https://golang.org/), originally developed specifically to support a Go language runtime. However, it was written in a  generic way such that it has since been adopted to implement runtimes for Swift, PHP, Python, Rust, Java, Ruby and Crystal. Even though it was developed with compiled languages in mind it works equally well with scripting languages.

Using it, you can develop a new runtime in a fraction of the time needed for authoring a full-fledged runtime from scratch. This is due to the fact that you have only to write a command line protocol and not a fully featured web server (with a small amount of corner case to take care of). The results should also produce a runtime that is fairly fast and responsive.  In fact, the ActionLoop proxy has also been adopted to improve the performance of existing runtimes like Python, Ruby, PHP, and Java where performance has improved by a factor between 2x to 20x.

ActionLoop also supports "precompilation". You can use the docker image of the runtime to compile your source files in an action offline. You will get a ZIP file that you can use as an action that is very fast to start because it contains only the binaries and not the sources. More information on this approach can be found here: [Precompiling Go Sources Offline](https://github.com/apache/incubator-openwhisk-runtime-go/blob/master/docs/DEPLOY.md#precompile) which describes how to do this for the Go language, but the approach applies to any language supported by ActionLoop.

In summary, it is likely that using the ActionLoop is simpler and a "better bet" than implementing the specification from scratch. If you are convinced and want to use it, then read on. What follows on this page is a tutorial on how to write an ActionLoop runtime, using Ruby as an example target language.

## How to write a new runtime with ActionLoop

The development procedure for ActionLoop requires the following steps:

* building a docker image containing your target language compiler and the ActionLoop runtime.
* writing a simple line-oriented protocol in your target language.
* writing a compilation script for your target language.
* writing some mandatory tests for your language.

To facilitate the process, there is an `actionloop-starter-kit` in the [openwhisk-devtools](https://github.com/apache/incubator-openwhisk-devtools/tree/master/actionloop-starter-kit) GitHub repository, that implements a fully working runtime for Python.  It contains a stripped-down version of the real Python runtime (with some advanced features removed) along with guided, step-by-step instructions on how to translate it to a different target runtime language using Ruby as an example.

In short, the starter kit provides templates you can adapt in creating an ActionLoop runtime for each of the steps listed above, these include :

- checking out  the `actionloop-starter-kit` from the `incubator-openwhisk-devtools` repository
- editing the `Dockerfile` to create the target environment for your target language.
- converting (rewrite) the `launcher.py` script to an equivalent for script for your target language.
- editing the `compile` script to compile your action in your target language.
- writing the mandatory tests for your target language, by adapting the `ActionLoopPythonBasicTests.scala` file.

As a starting language, we chose Python since it is one of the more human-readable languages (can be  treated as `pseudo-code`). Do not worry, you should only need just enough Python knowledge to be able to rewrite `launcher.py` and edit the `compile` script for your target language.

Finally, you will need to update the `ActionLoopPythonBasicTests.scala` test file which, although written in the Scala language, only serves as a wrapper that you will use to embed your target language tests into.

## Notation

In each step of this tutorial, we typically show snippets of either terminal transcripts (i.e., commands and results) or "diffs" of changes to existing code files.

Within terminal transcript snippets, comments are prefixed with `#` character and commands are prefixed by the `$` character. Lines that follow commands may include sample output (from their execution) which can be used to verify against results in your local environment.

When snippets show changes to existing source files, lines without a prefix should be left "as is", lines with `-` should be removed and lines with  `+` should be added.

## Prequisites

* Docker engine - please have a valid [docker engine installed](https://docs.docker.com/install/) that supports [multi-stage builds](https://docs.docker.com/develop/develop-images/multistage-build/) (i.e., Docker 17.05 or higher) and assure the Docker daemon is running.

```bash
# Verify docker version
$ docker --version
Docker version 18.09.3

# Verify docker is running
$ docker ps

# The result should be a valid response listing running processes
```

## Setup the development directory

So let's start to create our own `actionloop-demo-ruby-2.6` runtime. First, check out the `devtools` repository to access the starter kit, then move it in your home directory to work on it.

```bash
$ git clone https://github.com/apache/incubator-openwhisk-devtools
$ mv incubator-openwhisk-devtools/actionloop-starter-kit ~/actionloop-demo-ruby-v2.6
```

Now, take the directory `python3.7` and rename it to `ruby2.6` and use `sed` to fix the directory name references in the Gradle build files.

```bash
$ cd ~/actionloop-demo-ruby-v2.6
$ mv python3.7 ruby2.6
$ sed -i.bak -e 's/python3.7/ruby2.6/' settings.gradle
$ sed -i.bak -e 's/actionloop-demo-python-v3.7/actionloop-demo-ruby-v2.6/' ruby2.6/build.gradle
```

Let's check everything is fine building the image.

```bash
# building the image
$ ./gradlew distDocker
# ... intermediate output omitted ...
BUILD SUCCESSFUL in 1s
2 actionable tasks: 2 executed
# checking the image is available
$ docker images actionloop-demo-ruby-v2.6
REPOSITORY                  TAG                 IMAGE ID            CREATED             SIZE
actionloop-demo-ruby-v2.6   latest              df3e77c9cd8f        2 minutes ago          94.3MB
```

So we have built a new image `actionloop-demo-ruby-v2.6`. However, aside from the renaming, internally it will still contain a Python runtime which we will change as we continue in this tutorial.

## Preparing the Docker environment

Our language runtime's `Dockerfile` has the task of preparing an environment for executing OpenWhisk Actions.
Using the ActionLoop approach, we use a multistage Docker build to

1. derive our OpenWhisk language runtime from an existing Docker image that has all the target language's tools and libraries for running functions authored in that language. In our case, we will reference the `ruby:2.6.2-alpine3.9` image from the [Official Docker Images for Ruby](https://hub.docker.com/_/ruby) on Docker Hub.
1. leverage the existing `openwhisk/actionlooop-v2` image on Docker Hub from which we will "extract"  the *ActionLoop* proxy (i.e. copy `/bin/proxy` binary) our runtime will use to process Activation requests from the OpenWhisk platform and execute Actions by using the language's tools and libraries from step #1.

### Repurpose the renamed Python Dockerfile for Ruby builds

Let's edit the `ruby2.6/Dockerfile` to use, instead of the python image, the official ruby image on Docker Hub, and add our files:

```dockerfile
 FROM openwhisk/actionloop-v2:latest as builder
-FROM python:3.7-alpine
+FROM ruby:2.6.2-alpine3.9
 RUN mkdir -p /proxy/bin /proxy/lib /proxy/action
 WORKDIR /proxy
 COPY --from=builder /bin/proxy /bin/proxy
-ADD lib/launcher.py /proxy/lib/launcher.py
+ADD lib/launcher.rb /proxy/lib/launcher.rb
 ADD bin/compile /proxy/bin/compile
+RUN apk update && apk add python3
 ENV OW_COMPILER=/proxy/bin/compile
 ENTRYPOINT ["/bin/proxy"]
```

Next, let's rename the `launcher.py` (a Python script) to one that indicates it is a Ruby script named `launcher.rb`.

```bash
$ mv ruby2.6/lib/launcher.py ruby2.6/lib/launcher.rb
```

Note that:

1. You changed the base language for our target OpenWhisk runtime to use a Ruby language image.
1. You changed the launcher script to be a ruby script.
1. We had to add `python3` to our Ruby image since our `compile` script is written in Python for this tutorial. Of course, you could rewrite the entire `compile` script in Ruby if you wish to.

## Implementing the ActionLoop protocol

This section will take you through how to convert the contents of `launcher.rb` (formerly `launcher.py`) to the target Ruby programming language and implement the `ActionLoop protocol`.

### What the launcher should do

Let's recap the steps the launcher must accomplish to implement the `ActionLoop protocol` :

1. import the Action function's `main` method for execution.
    * Note: the `compile` script will make the function available to the launcher.
1. open the system's `file descriptor 3` which will be used to output the functions response.
1. read the system's standard input, `stdin`, line-by-line. Each line is parsed as a JSON string and produces a JSON object (not an array nor a scalar) to be passed as the input `arg` to the function.
    * Note: within the JSON object, the `value` key contains the user parameter data to be passed to your functions. All the other keys are made available as process environment variables to the function; these need to be uppercased and prefixed with `"__OW_"`.
1. invoke the `main` function with the JSON object payload.
1. encode the result of the function in JSON (ensuring it is only one line and it is terminated with one newline) and write it to `file descriptor 3`.
1. Once the function returns the result, flush the contents of `stdout`, `stderr` and `file descriptor 3` (FD 3).
1. Finally, include the above steps in a loop so that it continually looks for Activations. That's it.

### Converting launcher script to Ruby

Now, let's look at the protocol described above codified within the launcher script `launcher.rb` and work to convert its contents from Python to Ruby.

#### Import the function code

Skipping the first few library import statements within  `launcer.rb`, which we will have to resolve later after we determine which ones Ruby may need, we see the first significant line of code importing the actual Action function.

```python
# now import the action as process input/output
from main__ import main as main
```

In Ruby, this can be rewritten as:

```ruby
# requiring user's action code
require "./main__"
```

*Note that you are free to decide the path and filename for the function's source code. In our examples, we chose a base filename that includes the word `"main"` (since it is OpenWhisk's default function name) and append two underscores to better assure uniqueness.

#### Open File Descriptor (FD) 3 for function results output

The `ActionLoop` proxy expects to read the results of invoking the Action function from File Descriptor (FD) 3.

The existing Python:

```python
out = fdopen(3, "wb")
```

would be rewritten in Ruby as:

```ruby
out = IO.new(3)
```

#### Process Action's arguments from STDIN

Each time the function is invoked via an HTTP request, the `ActionLoop` proxy passes the message contents to the launcher via STDIN. The launcher must read STDIN line-by-line and parse it as JSON.

The `launcher`'s existing Python code reads STDIN line-by-line as follows:

```python
while True:
  line = stdin.readline()
  if not line: break
  # ...continue...
```

would be translated to Ruby as follows:

```ruby
while true
  # JSON arguments get passed via STDIN
  line = STDIN.gets()
  break unless line
  # ...continue...
end
```

Each line is parsed in JSON, where the `payload` is extracted from contents of the `"value"` key. Other keys and their values are as uppercased, `"__OW_"` prefixed environment variables:

The existing Python code for this is:

```python
  # ... continuing ...
  args = json.loads(line)
  payload = {}
  for key in args:
    if key == "value":
      payload = args["value"]
    else:
      os.environ["__OW_%s" % key.upper()]= args[key]
  # ... continue ...
```

would be translated to Ruby:

```ruby
  # ... continuing ...
  args = JSON.parse(line)
  payload = {}
  args.each do |key, value|
    if key == "value"
      payload = value
    else
      # set environment variables for other keys
      ENV["__OW_#{key.upcase}"] = value
    end
  end
  # ... continue ...
```

#### Invoking the Action function

You are now at the point of invoking the Action function and producing its result. *Note you **must** also capture exceptions and produce an `{"error": <result> }` if anything goes wrong during execution.*

The existing Python code for this is:

```python
  # ... continuing ...
  res = {}
  try:
    res = main(payload)
  except Exception as ex:
    print(traceback.format_exc(), file=stderr)
    res = {"error": str(ex)}
  # ... continue ...
```

would be translated to Ruby:

```ruby
  # ... continuing ...
  res = {}
  begin
    res = main(payload)
  rescue Exception => e
    puts "exception: #{e}"
    res ["error"] = "#{e}"
  end
  # ... continue ...
```

#### Flush File Descriptor 3, STDOUT and STDERR

Finally, you flush standard out and standard error and write the result back in file descriptor 3.

The existing Python code for this is:

```python
  out.write(json.dumps(res, ensure_ascii=False).encode('utf-8'))
  out.write(b'\n')
  stdout.flush()
  stderr.flush()
  out.flush()
```

would be translated to Ruby:

```ruby
  STDOUT.flush()
  STDERR.flush()
  out.puts(res.to_json)
  out.flush()
```

Congratulations! You wrote your ActionLoop handler.

## Writing the compilation script

Now, you need to write the compilation script. It is basically a script that will prepare the uploaded sources for execution, adding the launcher code and generating the final executable.

For interpreted languages, the compilation script will only "prepare" the sources for execution. The executable is simply a shell script to invoke the interpreter.

For compiled languages, like Go it will actually invoke a compiler in order to produce the final executable. There are also cases like Java where you still need to execute the compilation step that produces intermediate code, but the executable is just a shell script that will launch the Java runtime.

So let's go first examine how ActionLoop handles file upload, then what the provided compilation script does for Python, and finally we will see how to modify the existing `compile` for Python to work for Ruby.

### How the ActionLoop proxy handles action uploads

The OpenWhisk user can upload actions with the `wsk` tool only as a single file. This file, however, can be:
- a source file
- an executable file
- a zip file containing sources
- a zip file containing an executable and other support files

*Important*:  an executable for ActionLoop is either a Linux binary (an ELF executable) or a script. A script is, using Linux conventions, is anything starting with `#!`. The first line is interpreted as the command to use to launch the script: `#!/bin/bash`, `#!/usr/bin/python` etc.

The ActionLoop proxy accepts any file, prepares a work folder, with two folders in it: `src` and `bin`. Then it detects the format of the uploaded file.  For each case, the behavior is different.

If the uploaded file is an executable, it is stored as `bin/exec` and executed.

If the uploaded file is not an executable and not a zip file, it is stored as `src/exec` then the compilation script is invoked.

If the uploaded file is a zip file, it is unzipped in the `src` folder, then the `src/exec` file is checked.

If it exists and it is an executable, the folder `src` is renamed to `bin` and then again the `bin/exec` is executed.

If the `src/exec` is missing or is not an executable, then the compiler script is invoked.

### Compiling an action in source format

The compilation script is invoked only when the upload contains sources. According to the description in the past paragraph, if the upload is a single file, you can expect the file is in `src/exec`, without any prefix. Otherwise, sources are spread the `src` folder and it is the task of the compiler script to find the sources. A runtime may impose that when a zip file is uploaded, then there should be a fixed file with the main function. For example, the Python runtime expects the file `__main__.py`. However, it is not a rule: the Go runtime does not require any specific file as it compiles everything. It only requires a function with the name specified.

The compiler script goal is ultimately to leave in `bin/exec` an executable (implementing the ActionLoop protocol) that the proxy can launch. Also, if the executable is not standalone, other files must be stored in this folder, since the proxy can also zip all of them and send to the user when using the pre-compilation feature.

The compilation script is a script pointed by the `OW_COMPILER` environment variable (you may have noticed it in the Dockerfile) that will be invoked with 3 parameters:

1. `<main>` is the name of the main function specified by the user on the `wsk` command line
1. `<src>` is the absolute directory with the sources already unzipped
1.  an empty `<bin>` directory where you are expected to place your final executables

Note that both the `<src>` and `<bin>` are disposable, so you can do things like removing the `<bin>` folder and rename the `<src>`.

Since the user generally only sends a function specified by the `<main>` parameter, you have to add the launcher you wrote and adapt it to execute the function.

### Implementing the `compile` for Ruby

This is the algorithm that the `compile` script in the kit follows for Python:

1. if there is a `<src>/exec` it must rename to the main file; I use the name `main__.py`
1. if there is a `<src>/__main__.py` it will rename to the main file `main__.py`
1.  copy the `launcher.py` to `exec__.py`, replacing the `main(arg)` with `<main>(arg)`;  this file imports the `main__.py` and invokes the function `<main>`
1. add a launcher script `<src>/exec`
1. finally it removes the `<bin>` folder and rename `<src>` to `<bin>`

You can adapt this algorithm easily to Ruby with just a few changes.

The script defines the functions `sources` and `build` then starts the execution, at the end of the script.

Start from the end of the script, where the script collect parameters from the command line. Instead of `launcher.py`, use `launcher.rb`:

```
- launcher = "%s/lib/launcher.py" % dirname(dirname(sys.argv[0]))
+ launcher = "%s/lib/launcher.rb" % dirname(dirname(sys.argv[0]))
```

Then the script invokes the `source` function. This function renames the `exec` file to `main__.py`, you will rename it instead to `main__.rb`:

```
- copy_replace(src_file, "%s/main__.py" % src_dir)
+ copy_replace(src_file, "%s/main__.rb" % src_dir)
```

If instead there is a `__main__.py` the function will rename to `main__.py` (the launcher invokes this file always). The Ruby runtime will use a `main.rb` as starting point. So the next change is:

```
- # move __main__ in the right place if it exists
- src_file = "%s/__main__.py" % src_dir
+ # move main.rb in the right place if it exists
+ src_file = "%s/main.rb" % src_dir
```

Now, the `source` function  copies the launcher as `exec__.py`, replacing the line `from main__ import main as main` (invoking the main function) with `from main__ import <main> as main`. In Ruby you may want to replace the line `res = main(payload)` with `res = <main>(payload)`. In code it is:

```
- copy_replace(launcher, "%s/exec__.py" % src_dir,
-   "from main__ import main as main",
-    "from main__ import %s as main" % main )
+ copy_replace(launcher, "%s/exec__.rb" % src_dir,
+    "res = main(payload)",
+     "res = %s(payload)" % main )
```

You are almost done. You just need the startup script that instead of invoking python will invoke Ruby. So in the `build` function do this change:

```
 write_file("%s/exec" % tgt_dir, """#!/bin/sh
 cd "$(dirname $0)"
-exec /usr/local/bin/python exec__.py
+exec ruby exec__.rb
 """)
```

For an interpreted language that is all. You move the `src` folder in the `bin`. For a compiled language instead, you may want to actually invoke the compiler to produce the executable.

## Debugging

Now you wrote your `launcher` and `compile`, it is time to test it.  Here will see:

1. how to enter in a test environment
1. simple smoke tests to check things work
1. writing the validation tests
1. testing the image in an actual OpenWhisk environment


### Entering in the test environment

In the starter kit, there is a `Makefile` that can help with your development efforts.

You can build the Dockerfile using the provided Makefile. Since it has a reference to the image we are building, let's change it:

```
sed -i.bak -e 's/actionloop-demo-python-v3.7/actionloop-demo-ruby-v2.6/' ruby2.6/Makefile
```

You should be now able to build the image and enter in it with  `make debug`. It will rebuild the image for you and put you in a shell so you can enter in your image environment for testing and debugging:

```
$ cd ruby2.6
$ make debug
... omissis ...
#
```

Let's start with a couple of notes about this test environment.

First,  it works setting `--entrypoint=/bin/sh` so you need to have a shell in your Docker image. Generally, this is true, but in some stripped down base images, there is not even a shell so it will not work.

Second, the `/proxy` folder is mounted in your local directory, so you can edit the `bin/compile` and the `lib/launcher.rb` using your editor outside the Docker image

*NOTE* You do not have to rebuild the Docker image at every change if you use the `make debug`. Directories and environment variables are set in a way your proxy will use the code outside the Docker container so that you can edit it freely.

You are now at the shell prompt that we will use for development. You will have to start and stop the proxy from the shell prompt, as we will see. The shell will help you to inspect what happened inside the container.

### A simple smoke test

It is time to test. Let's write a very simple test first, converting the `example\hello.py` in `example\hello.rb` as follows:

```
def hello(args)
  name = args["name"] || "stranger"
  greeting = "Hello #{name}!"
  puts greeting
  { "greeting" => greeting }
end
```

You have a test script. So now *change directory to subdirectory* `ruby2.6` of your runtime project and in one terminal type:

```
$ cd <projectdir>/ruby2.6
$ make debug
... omissis ...
(you should see a shell prompt of your image)
$ /bin/proxy -debug
2019/04/08 07:47:36 OpenWhisk ActionLoop Proxy 2: starting
```

Now you the runtime, in debug mode, listening in port 8080 and ready to accept action deployments. In `tools/invoke.py` there is a script allowing to simulate action deployment.

So now open another terminal (while leaving the first ore running), and go *in the top-level directory of your project* and test your action, executing an `init` and then a  couple of `run`.

This is what you should see if everything is correct:

```
$ cd <projectdir>
$ python tools/invoke.py init hello example/hello.rb
{"ok":true}
$ python tools/invoke.py run  '{}'
{"greeting":"Hello stranger!"}
$ python tools/invoke.py run  '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```

Also, this is the output you should see in the terminal running the proxy. This output helps you to track what is happening

```
/proxy # /bin/proxy -debug
2019/04/08 07:54:57 OpenWhisk ActionLoop Proxy 2: starting
2019/04/08 07:58:00 compiler: /proxy/bin/compile
2019/04/08 07:58:00 it is source code
2019/04/08 07:58:00 compiling: ./action/16/src/exec main: hello
2019/04/08 07:58:00 compiling: /proxy/bin/compile hello action/16/src action/16/bin
2019/04/08 07:58:00 compiler out: , <nil>
2019/04/08 07:58:00 env: [__OW_API_HOST=]
2019/04/08 07:58:00 starting ./action/16/bin/exec
2019/04/08 07:58:00 Start:
2019/04/08 07:58:00 pid: 13
2019/04/08 07:58:24 done reading 13 bytes
Hello stranger!
XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
2019/04/08 07:58:24 received::{"greeting":"Hello stranger!"}
2019/04/08 07:58:54 done reading 27 bytes
Hello Mike!
XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
2019/04/08 07:58:54 received::{"greeting":"Hello Mike!"}
```

### Hints and tips for debugging

Of course, it is very possible something went wrong. Here a few debugging suggestions:

You can `init` only once. So if you want to re-initialize your runtime, you have to press control-c and restart it

Check what is in the action folder. The proxy creates a numbered folder under `action` and then an `src` and `bin` folder. So for a single file action can see this:

From the first terminal, type:

```
$ find
action/
action/1
action/1/bin
action/1/bin/exec__.rb
action/1/bin/exec
action/1/bin/main__.rb
```

You can note the `exec` starter, the `exec__.rb` launcher and your action `main__.rb`

You can try to run the action directly to see if it behaves properly:

```
$ cd action/1/bin
$ ./exec 3>&1
$ {"value":{"name":"Mike"}}
Hello Mike!
{"greeting":"Hello Mike!"}
```

Note you redirected the file descriptor 3 in stdout to check what is happening, and note that logs appear in stdout too.

Also, you can test the compiler invoking it directly.

First let's prepare the environment as it appears when you just uploaded the action:

```
$ cd /proxy
$ mkdir -p action/2/src action/2/bin
$ cp action/1/bin/main__.rb action/2/src/exec
$ find action/2
action/2
action/2/bin
action/2/src
action/2/src/exec
```

Now compile and see the results:

```
$ /proxy/bin/compile main action/2/src action/2/bin
$ find action/2
action/2/
action/2/bin
action/2/bin/exec__.rb
action/2/bin/exec
action/2/bin/main__.rb
```

## Testing

If you got here, your runtime is able to run and execute a simple action. Of course, there is more to test. You should:

1. test more cases in your local runtime
1. write all the mandatory tests
1. test your runtime against a real OpenWhisk

### Testing the various cases

So far you tested a single file action. You should also test multi-file actions send as sources and also multi-file actions sent as binaries.

So first let's do a multi-file action. In ruby, let's add an `example/main.rb` that invokes our `hello.rb` as follows:

```
require "./hello"
def main(args)
    hello(args)
end
```

There are also two handy makefiles for tests, you should change in the `example/Makefile` the name of the image and the name of the main action. Also, you need an account on DockerHub. So if your username on DockerHub is, for example, `linus`, change as follows:

```
-IMG=actionloop-demo-python-v3.7:latest
-ACT=hello-demo-python
-PREFIX=docker.io/openwhisk
+IMG=actionloop-demo-ruby-v2.6:latest
+ACT=hello-demo-ruby
+PREFIX=docker.io/linus
```

Now, you are ready to test the various cases. First, start the runtime in debug mode:

```
$ cd ruby2.6
$ make debug
$ /bin/proxy -debug
```

On another terminal, try to deploy a single file:

```
$ make test-single
python ../tools/invoke.py init hello ../example/hello.rb
{"ok":true}

python ../tools/invoke.py run '{}'
{"greeting":"Hello stranger!"}

python ../tools/invoke.py run '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```

Now, *stop and restart the proxy* and try to send a zip file with the sources:

```
$ make test-src-zip
zip src.zip main.rb hello.rb
  adding: main.rb (deflated 42%)
  adding: hello.rb (deflated 42%)
python ../tools/invoke.py init ../example/src.zip
{"ok":true}

python ../tools/invoke.py run '{}'
{"greeting":"Hello stranger!"}

python ../tools/invoke.py run '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```

Finally, test the pre-compilation: the runtime builds a zip file with the sources ready to be deployed. Again, *stop and restart the proxy* then:

```
$ make test-bin-zip
docker run -i actionloop-demo-ruby-v2.6:latest -compile main <src.zip >bin.zip
python ../tools/invoke.py init ../example/bin.zip
{"ok":true}

python ../tools/invoke.py run '{}'
{"greeting":"Hello stranger!"}

python ../tools/invoke.py run '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```
Congratulations! The runtime at least locally works. Time to test it on the public cloud. So as the last step before moving forward, let's push the image to Docker Hub with `make push`.

### Testing on OpenWhisk

To run this test you need to configure access to OpenWhisk with `wsk`. A simple way is to get access is to register a free account in the IBM Cloud but this works also with your own deployment of OpenWhisk.

Edit the Makefile and ensure it starts with the right docker image and the right prefix. For example, if you are `linus` it should be:

```
IMG=actionloop-demo-ruby-v2.6:latest
ACT=hello-demo-ruby
PREFIX=docker.io/linux
```

Also, change any reference to `hello.py` and `main.py` to `hello.rb` and `main.rb`.

Once this is done you can re-run the tests you executed locally on "the real thing".

Test single:

```
$ make test-single
wsk action update hello-demo-ruby hello.rb --docker docker.io/linus/actionloop-demo-ruby-v2.6:latest --main hello
ok: updated action hello-demo-ruby
wsk action invoke hello-demo-ruby -r
{
    "greeting": "Hello stranger!"
}
wsk action invoke hello-demo-ruby -p name Mike -r
{
    "greeting": "Hello Mike!"
}
```

Test source zip:

```
$ make test-src-zip
zip src.zip main.rb hello.rb
  adding: main.rb (deflated 42%)
  adding: hello.rb (deflated 42%)
wsk action update hello-demo-ruby src.zip --docker docker.io/linus/actionloop-demo-ruby-v2.6:latest
ok: updated action hello-demo-ruby
wsk action invoke hello-demo-ruby -r
{
    "greeting": "Hello stranger!"
}
wsk action invoke hello-demo-ruby -p name Mike -r
{
    "greeting": "Hello Mike!"
}
```

Test binary zip:

```
$ make test-bin-zip
docker run -i actionloop-demo-ruby-v2.6:latest -compile main <src.zip >bin.zip
wsk action update hello-demo-ruby bin.zip --docker docker.io/actionloop/actionloop-demo-ruby-v2.6:latest
ok: updated action hello-demo-ruby
wsk action invoke hello-demo-ruby -r
{
    "greeting": "Hello stranger!"
}
wsk action invoke hello-demo-ruby -p name Mike -r
{
    "greeting": "Hello Mike!"
}
```

Congratulations! Your runtime works also in the real world.

### Writing the validation tests

Before you can submit your runtime you should ensure your runtime pass the validation tests.

Under `tests/src/test/scala/runtime/actionContainers/ActionLoopPythonBasicTests.scala` there is the template for the test.

Rename to `tests/src/test/scala/runtime/actionContainers/ActionLoopRubyBasicTests.scala`, change internally the class name to `class ActionLoopRubyBasicTests` and implement the following test cases:

- `testNotReturningJson`
- `testUnicode`
- `testEnv`
- `testInitCannotBeCalledMoreThanOnce`
- `testEntryPointOtherThanMain`
- `testLargeInput`

You should convert Python code to Ruby code. We do not do go into the details of each test, as they are pretty simple and obvious. You can check the source code for the real test [here](https://github.com/apache/incubator-openwhisk-runtime-ruby/blob/master/tests/src/test/scala/actionContainers/Ruby26ActionLoopContainerTests.scala).

You can verify tests are running properly with:

```
$ ./gradlew test

Starting a Gradle Daemon, 1 busy Daemon could not be reused, use --status for details

> Task :tests:test

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should handle initialization with no code PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should handle initialization with no content PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should run and report an error for function not returning a json object PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should fail to initialize a second time PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should invoke non-standard entry point PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should echo arguments and print message to stdout/stderr PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should handle unicode in source, input params, logs, and result PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should confirm expected environment variables PASSED

runtime.actionContainers.ActionLoopPythoRubyTests > runtime proxy should echo a large input PASSED

BUILD SUCCESSFUL in 55s
```

If you got here, congratulations! Your runtime is ready for being submitted and included in OpenWhisk.
