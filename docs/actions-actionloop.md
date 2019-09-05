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

The [OpenWhisk runtime specification](actions-new.md) defines the expected behavior of an OpenWhisk runtime; you can choose to implement a new runtime from scratch by just following this specification. However, the fastest way to develop a new, compliant runtime is by reusing the *[ActionLoop proxy](https://github.com/apache/openwhisk-runtime-go#actionloop-runtime)* which already implements most of the specification and requires you to write code for just a few hooks to get a fully functional (and *fast*) runtime in a few hours or less.

## What is the ActionLoop proxy

The `ActionLoop proxy` is a runtime "engine", written in the [Go programming language](https://golang.org/), originally developed specifically to support the [OpenWhisk Go language runtime](https://github.com/apache/openwhisk-runtime-go). However, it was written in a  generic way such that it has since been adopted to implement OpenWhisk runtimes for Swift, PHP, Python, Rust, Java, Ruby and Crystal. Even though it was developed with compiled languages in mind it works equally well with scripting languages.

Using it, you can develop a new runtime in a fraction of the time needed for authoring a full-fledged runtime from scratch. This is due to the fact that you have only to write a command line protocol and not a fully-featured web server (with a small amount of corner cases to consider). The results should also produce a runtime that is fairly fast and responsive.  In fact, the ActionLoop proxy has also been adopted to improve the performance of existing runtimes like Python, Ruby, PHP, and Java where performance has improved by a factor between 2x to 20x.

### Precompilation of OpenWhisk Actions

In addition to being the basis for new runtime development,  ActionLoop runtimes can also support offline "precompilation" of OpenWhisk Action source files into a ZIP file that contains only the compiled binaries which are very fast to start once deployed. More information on this approach can be found here: [Precompiling Go Sources Offline](https://github.com/apache/openwhisk-runtime-go/blob/master/docs/DEPLOY.md#precompile) which describes how to do this for the Go language, but the approach applies to any language supported by ActionLoop.

# Tutorial - How to write a new runtime with the ActionLoop Proxy

This section contains a stepwise tutorial which will take you through the process of developing a new ActionLoop runtime using the Ruby language as the example.

## General development process

The general procedure for authoring a runtime with the `ActionLoop proxy` requires the following steps:

* building a docker image containing your target language compiler and the ActionLoop runtime.
* writing a simple line-oriented protocol in your target language.
* writing a compilation script for your target language.
* writing some mandatory tests for your language.

## ActionLoop Starter Kit

To facilitate the process, there is an `actionloop-starter-kit` in the [openwhisk-devtools](https://github.com/apache/openwhisk-devtools/tree/master/actionloop-starter-kit) GitHub repository, that implements a fully working runtime for Python.  It contains a stripped-down version of the real Python runtime (with some advanced features removed) along with guided, step-by-step instructions on how to translate it to a different target runtime language using Ruby as an example.

In short, the starter kit provides templates you can adapt in creating an ActionLoop runtime for each of the steps listed above, these include :

-checking out  the `actionloop-starter-kit` from the `openwhisk-devtools` repository
-editing the `Dockerfile` to create the target environment for your target language.
-converting (rewrite) the `launcher.py` script to an equivalent for script for your target language.
-editing the `compile` script to compile your action in your target language.
-writing the mandatory tests for your target language, by adapting the `ActionLoopPythonBasicTests.scala` file.

As a starting language, we chose Python since it is one of the more human-readable languages (can be  treated as `pseudo-code`). Do not worry, you should only need just enough Python knowledge to be able to rewrite `launcher.py` and edit the `compile` script for your target language.

Finally, you will need to update the `ActionLoopPythonBasicTests.scala` test file which, although written in the Scala language, only serves as a wrapper that you will use to embed your target language tests into.

## Notation

In each step of this tutorial, we typically show snippets of either terminal transcripts (i.e., commands and results) or "diffs" of changes to existing code files.

Within terminal transcript snippets, comments are prefixed with `#` character and commands are prefixed by the `$` character. Lines that follow commands may include sample output (from their execution) which can be used to verify against results in your local environment.

When snippets show changes to existing source files, lines without a prefix should be left "as is", lines with `-` should be removed and lines with  `+` should be added.

## Prerequisites

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
$ git clone https://github.com/apache/openwhisk-devtools
$ mv openwhisk-devtools/actionloop-starter-kit ~/actionloop-demo-ruby-v2.6
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

At this point, we have built a new image named `actionloop-demo-ruby-v2.6`. However, despite having `Ruby` in the name, internally it still is a `Python` language runtime which we will need to change to one supporting `Ruby` as we continue in this tutorial.

## Preparing the Docker environment

Our language runtime's `Dockerfile` has the task of preparing an environment for executing OpenWhisk Actions.
Using the ActionLoop approach, we use a multistage Docker build to

1. derive our OpenWhisk language runtime from an existing Docker image that has all the target language's tools and libraries for running functions authored in that language.
    * In our case, we will reference the `ruby:2.6.2-alpine3.9` image from the [Official Docker Images for Ruby](https://hub.docker.com/_/ruby) on Docker Hub.
1. leverage the existing `openwhisk/actionlooop-v2` image on Docker Hub from which we will "extract" the *ActionLoop* proxy (i.e. copy `/bin/proxy` binary) our runtime will use to process Activation requests from the OpenWhisk platform and execute Actions by using the language's tools and libraries from step #1.

## Repurpose the renamed Python Dockerfile for Ruby builds

Let's edit the `ruby2.6/Dockerfile` to use the official Ruby image on Docker Hub as our base image, instead of a Python image, and add our our Ruby launcher script:

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

1. You changed the base Docker image to use a `Ruby` language image.
1. You changed the launcher script from `Python` to `Ruby`.
1. We had to add a `python3` package to our Ruby image since our `compile` script will be written in Python for this tutorial. Of course, you may choose to rewrite the `compile` script in `Ruby` if you wish to as your own exercise.

## Implementing the ActionLoop protocol

This section will take you through how to convert the contents of `launcher.rb` (formerly `launcher.py`) to the target Ruby programming language and implement the `ActionLoop protocol`.

### What the launcher needs to do

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

Now, let's look at the protocol described above, codified within the launcher script `launcher.rb`, and work to convert its contents from Python to Ruby.

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

*Note that you are free to decide the path and filename for the function's source code. In our examples, we chose a base filename that includes the word `"main"` (since it is OpenWhisk's default function name) and append two underscores to better assure uniqueness.*

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

We are now at the point of invoking the Action function and producing its result. *Note we **must** also capture exceptions and produce an `{"error": <result> }` if anything goes wrong during execution.*

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

#### Finalize File Descriptor (FD) 3, STDOUT and STDERR

Finally, we need to write the function's result to File Descriptor (FD) 3 and "flush" standard out (stdout), standard error (stderr) and FD 3.

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

Congratulations! You just completed your `ActionLoop` request handler.

## Writing the compilation script

Now, we need to write the `compilation script`. It is basically a script that will prepare the uploaded sources for execution, adding the `launcher` code and generate the final executable.

For interpreted languages, the compilation script will only "prepare" the sources for execution. The executable is simply a shell script to invoke the interpreter.

For compiled languages, like Go it will actually invoke a compiler in order to produce the final executable. There are also cases like Java where we still need to execute the compilation step that produces intermediate code, but the executable is just a shell script that will launch the Java runtime.

### How the ActionLoop proxy handles action uploads

The OpenWhisk user can upload actions with the `wsk` Command Line Interface (CLI) tool as a single file.

This single file can be:

- a source file
- an executable file
- a ZIP file containing sources
- a ZIP file containing an executable and other support files

*Important*:  an executable for ActionLoop is either a Linux binary (an ELF executable) or a script. A script is, using Linux conventions, is anything starting with `#!`. The first line is interpreted as the command to use to launch the script: `#!/bin/bash`, `#!/usr/bin/python` etc.

The ActionLoop proxy accepts any file, prepares a work folder, with two folders in it named `"src"` and `"bin"`. Then it detects the format of the uploaded file.  For each case, the behavior is different.

- If the uploaded file is an executable, it is stored as `bin/exec` and executed.
- If the uploaded file is not an executable and not a zip file, it is stored as `src/exec` then the compilation script is invoked.
- If the uploaded file is a zip file, it is unzipped in the `src` folder, then the `src/exec` file is checked.
- If it exists and it is an executable, the folder `src` is renamed to `bin` and then again the `bin/exec` is executed.
- If the `src/exec` is missing or is not an executable, then the compiler script is invoked.

### Compiling an action in source format

The compilation script is invoked only when the upload contains sources. According to the description in the past paragraph, if the upload is a single file, we can expect the file is in `src/exec`, without any prefix. Otherwise, sources are spread the `src` folder and it is the task of the compiler script to find the sources. A runtime may impose that when a zip file is uploaded, then there should be a fixed file with the main function. For example, the Python runtime expects the file `__main__.py`. However, it is not a rule: the Go runtime does not require any specific file as it compiles everything. It only requires a function with the name specified.

The compiler script goal is ultimately to leave in `bin/exec` an executable (implementing the ActionLoop protocol) that the proxy can launch. Also, if the executable is not standalone, other files must be stored in this folder, since the proxy can also zip all of them and send to the user when using the pre-compilation feature.

The compilation script is a script pointed by the `OW_COMPILER` environment variable (you may have noticed it in the Dockerfile) that will be invoked with 3 parameters:

1. `<main>` is the name of the main function specified by the user on the `wsk` command line
1. `<src>` is the absolute directory with the sources already unzipped
1.  an empty `<bin>` directory where we are expected to place our final executables

Note that both the `<src>` and `<bin>` are disposable, so we can do things like removing the `<bin>` folder and rename the `<src>`.

Since the user generally only sends a function specified by the `<main>` parameter, we have to add the launcher we wrote and adapt it to execute the function.

### Implementing the `compile` for Ruby

This is the algorithm that the `compile` script in the kit follows for Python:

1. if there is a `<src>/exec` it must rename to the main file; I use the name `main__.py`
1. if there is a `<src>/__main__.py` it will rename to the main file `main__.py`
1. copy the `launcher.py` to `exec__.py`, replacing the `main(arg)` with `<main>(arg)`;  this file imports the `main__.py` and invokes the function `<main>`
1. add a launcher script `<src>/exec`
1. finally it removes the `<bin>` folder and rename `<src>` to `<bin>`

We can adapt this algorithm easily to Ruby with just a few changes.

The script defines the functions `sources` and `build` then starts the execution, at the end of the script.

Start from the end of the script, where the script collect parameters from the command line. Instead of `launcher.py`, use `launcher.rb`:

```
- launcher = "%s/lib/launcher.py" % dirname(dirname(sys.argv[0]))
+ launcher = "%s/lib/launcher.rb" % dirname(dirname(sys.argv[0]))
```

Then the script invokes the `source` function. This function renames the `exec` file to `main__.py`, you will rename it instead to `main__.rb`:

```ruby
- copy_replace(src_file, "%s/main__.py" % src_dir)
+ copy_replace(src_file, "%s/main__.rb" % src_dir)
```

If instead there is a `__main__.py` the function will rename to `main__.py` (the launcher invokes this file always). The Ruby runtime will use a `main.rb` as starting point. So the next change is:

```ruby
- # move __main__ in the right place if it exists
- src_file = "%s/__main__.py" % src_dir
+ # move main.rb in the right place if it exists
+ src_file = "%s/main.rb" % src_dir
```

Now, the `source` function  copies the launcher as `exec__.py`, replacing the line `from main__ import main as main` (invoking the main function) with `from main__ import <main> as main`. In Ruby you may want to replace the line `res = main(payload)` with `res = <main>(payload)`. In code it is:

```ruby
- copy_replace(launcher, "%s/exec__.py" % src_dir,
-   "from main__ import main as main",
-    "from main__ import %s as main" % main )
+ copy_replace(launcher, "%s/exec__.rb" % src_dir,
+    "res = main(payload)",
+     "res = %s(payload)" % main )
```

We are almost done. We just need the startup script that instead of invoking python will invoke Ruby. So in the `build` function do this change:

```ruby
 write_file("%s/exec" % tgt_dir, """#!/bin/sh
 cd "$(dirname $0)"
-exec /usr/local/bin/python exec__.py
+exec ruby exec__.rb
 """)
```

For an interpreted language that is all. We move the `src` folder in the `bin`. For a compiled language instead, we may want to actually invoke the compiler to produce the executable.

## Debugging

Now that we have completed both the `launcher` and `compile` scripts, it is time to test them.

Here we will learn how to:

1. enter in a test environment
1. simple smoke tests to check things work
1. writing the validation tests
1. testing the image in an actual OpenWhisk environment


### Entering in the test environment

In the starter kit, there is a `Makefile` that can help with our development efforts.

We can build the Dockerfile using the provided Makefile. Since it has a reference to the image we are building, let's change it:

```bash
sed -i.bak -e 's/actionloop-demo-python-v3.7/actionloop-demo-ruby-v2.6/' ruby2.6/Makefile
```

We should be now able to build the image and enter in it with `make debug`. It will rebuild the image for us and put us into a shell so we can enter access the image environment for testing and debugging:

```bash
$ cd ruby2.6
$ make debug
# results omitted for brevity ...
```

Let's start with a couple of notes about this test environment.

First, use `--entrypoint=/bin/sh` when starting the image to have a shell available at our image entrypoint. Generally, this is true by default; however, in some stripped down base images a shell may not be available.

Second, the `/proxy` folder is mounted in our local directory, so that we can edit the `bin/compile` and the `lib/launcher.rb` using our editor outside the Docker image

*NOTE* It is not necessary to rebuild the Docker image with every change when using `make debug` since directories and environment variables used by the proxy indicate where the code outside the Docker container is located.

Once at the shell prompt that we will use for development,  we will have to start and stop the proxy. The shell will help us to inspect what happened inside the container.

### A simple smoke test

It is time to test. Let's write a very simple test first, converting the `example\hello.py` in `example\hello.rb` to appear as follows:

```ruby
def hello(args)
  name = args["name"] || "stranger"
  greeting = "Hello #{name}!"
  puts greeting
  { "greeting" => greeting }
end
```

Now change into the `ruby2.6` subdirectory of our runtime project and in one terminal type:

```bash
$ cd <projectdir>/ruby2.6
$ make debug
# results omitted for brevity ...
# (you should see a shell prompt of your image)
$ /bin/proxy -debug
2019/04/08 07:47:36 OpenWhisk ActionLoop Proxy 2: starting
```

Now the runtime is started in debug mode, listening on port 8080, and ready to accept Action deployments.

Open another terminal (while leaving the first one running the proxy) and go *into the top-level directory of our project* to test the Action by executing an `init` and then a couple of `run` requests using the `tools/invoke.py` test script.

These steps should look something like this in the second terminal:

```bash
$ cd <projectdir>
$ python tools/invoke.py init hello example/hello.rb
{"ok":true}
$ python tools/invoke.py run '{}'
{"greeting":"Hello stranger!"}
$ python tools/invoke.py run  '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```

We should also see debug output from the first terminal running the proxy (with the `debug` flag) which should have successfully processed the `init` and `run` requests above.

The proxy's debug output should appear something like:

```bin
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

The ActionLoop runtime (proxy) can only be initialized once using the `init` command from the `invoke.py` script. If we need to re-initialize the runtime, we need to stop the runtime (i.e., with Control-C)  and restart it.

We can also check what is in the action folder. The proxy creates a numbered folder under `action` and then a `src` and `bin` folder.

For example, using a terminal window, we would would see a directory and file structure created by a single action:

```bash
$ find
action/
action/1
action/1/bin
action/1/bin/exec__.rb
action/1/bin/exec
action/1/bin/main__.rb
```

Note that the `exec` starter, `exec__.rb` launcher and `main__.rb` action code are have all been copied under a directory numbered`1`.

In addition, we can try to run the action directly and see if it behaves properly:

```bash
$ cd action/1/bin
$ ./exec 3>&1
$ {"value":{"name":"Mike"}}
Hello Mike!
{"greeting":"Hello Mike!"}
```

Note we redirected the file descriptor 3 in stdout to check what is happening, and note that logs appear in stdout too.

Also, we can test the compiler invoking it directly.

First let's prepare the environment as it appears when we just uploaded the action:

```bash
$ cd /proxy
$ mkdir -p action/2/src action/2/bin
$ cp action/1/bin/main__.rb action/2/src/exec
$ find action/2
action/2
action/2/bin
action/2/src
action/2/src/exec
```

Now compile and examine the results again:

```bash
$ /proxy/bin/compile main action/2/src action/2/bin
$ find action/2
action/2/
action/2/bin
action/2/bin/exec__.rb
action/2/bin/exec
action/2/bin/main__.rb
```

## Testing

If we have reached this point in the tutorial, the runtime is able to run and execute a simple test action. Now we need to validate the runtime against a set of mandatory tests both locally and within an OpenWhisk staging environment. Additionally, we should author and automate additional tests for language specific features and styles.

The `starter kit` includes two handy `makefiles` that we can leverage for some additional tests. In the next sections, we will show how to update them for testing our Ruby runtime.

### Testing multi-file Actions

So far we tested a only an Action comprised of a single file. We should also test multi-file Actions (i.e., those with relative imports) sent to the runtime in both source and binary formats.

First, let's try a multi-file Action by creating a Ruby Action script named `example/main.rb` that invokes our `hello.rb` as follows:

```ruby
require "./hello"
def main(args)
    hello(args)
end
```

Within the `example/Makefile` makefile:

- update the name of the image to `ruby-v2.6"` as well as the name of the `main` action.
- update the PREFIX with your DockerHub username.

```makefile
-IMG=actionloop-demo-python-v3.7:latest
-ACT=hello-demo-python
-PREFIX=docker.io/openwhisk
+IMG=actionloop-demo-ruby-v2.6:latest
+ACT=hello-demo-ruby
+PREFIX=docker.io/<docker username>
```

Now, we are ready to test the various cases. Again, start the runtime proxy in debug mode:

```bash
$ cd ruby2.6
$ make debug
$ /bin/proxy -debug
```

On another terminal, try to deploy a single file:

```bash
$ make test-single
python ../tools/invoke.py init hello ../example/hello.rb
{"ok":true}
python ../tools/invoke.py run '{}'
{"greeting":"Hello stranger!"}
python ../tools/invoke.py run '{"name":"Mike"}'
{"greeting":"Hello Mike!"}
```

Now, *stop and restart the proxy* and try to send a ZIP file with the sources:

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

Congratulations! The runtime works locally! Time to test it on the public cloud. So as the last step before moving forward, let's push the image to Docker Hub with `make push`.

### Testing on OpenWhisk

To run this test you need to configure access to OpenWhisk with `wsk`. A simple way is to get access is to register a free account in the IBM Cloud but this works also with our own deployment of OpenWhisk.

Edit the Makefile as we did previously:

```makefile
IMG=actionloop-demo-ruby-v2.6:latest
ACT=hello-demo-ruby
PREFIX=docker.io/<docker username>
```

Also, change any reference to `hello.py` and `main.py` to `hello.rb` and `main.rb`.

Once this is done, we can re-run the tests we executed locally on "the real thing".

Test single:

```bash
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

```bash
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

Test binary ZIP:

```bash
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

You should convert Python code to Ruby code. We do not do go into the details of each test, as they are pretty simple and obvious. You can check the source code for the real test [here](https://github.com/apache/openwhisk-runtime-ruby/blob/master/tests/src/test/scala/actionContainers/Ruby26ActionLoopContainerTests.scala).

You can verify tests are running properly with:

```bash
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

Big congratulations are in order having reached this point successfully. At this point, our runtime should be ready to run on any OpenWhisk platform and also can be submitted for consideration to be included in the Apache OpenWhisk project.
