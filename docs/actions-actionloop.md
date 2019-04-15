
# Developing a new Runtime with the ActionLoop proxy

The [runtime specification](actions-new.md) defines the expected behavior of a runtime. You can implement a runtime from scratch just following the spec.

However, the fastest way to develop a new runtime is reusing the *ActionLoop* proxy, that already implements the specification and provides just a few hooks to get a fully functional (and *fast*) runtime in a few hours.

## What is the ActionLoop proxy

ActionLoop proxy is a runtime "engine", written in Go, originally developed precisely to support the Go language. However it was written in a pretty generic way, and it has been then adopted also to implement runtimes for Swift, PHP, Python, Rust, Java, Ruby and Crystal. It was developed with compiled languages in minds but works well also with scripting languages.

Using it, you can develop a new runtime in a fraction of the time needed for a full-fledged runtime, since you have only to write a command line protocol and not a fully featured web server, with an amount of corner case to take care.

Also, you will likely get a pretty fast runtime, since it is currently the most rapid. It was also adopted to improve performances of existing runtimes, that gained from factor 2x to a factor 20x for languages like Python, Ruby, PHP, and Java.

ActionLoop also supports "precompilation". You can take a raw image and use the docker image to perform the transformation in action. You will get a zip file that you can use as an action that is very fast to start because it contains only the binaries and not the sources.

So it is likely are using ActionLoop a better bet than implementing the specification from scratch. If you are convinced and want to use it, read on: this page is a tutorial on how to write an ActionLoop runtime, using Ruby as an example.

## How to write a new runtime with ActionLoop

The development procedure for ActionLoop requires the following steps:

* building a docker image containing your target language compiler and the ActionLoop runtime
*  writing a simple line-oriented protocol in your target language (converting a python example)
* write (or just adapt the existing) a compilation script for your target language
* write some mandatory tests for your language

To facilitate the process, there is an `actionloop-starter-kit` in the devtools repository, that implements a fully working runtime for Python.  It is a stripped down version of the real Python runtime (removing some advanced details of the real one).

So you can implement your runtime translating some Python code in your target language. This tutorial shows step by step how to do it writing the Ruby runtime. This code is also used in the real Ruby runtime.

Using the starter kit, the process becomes:

- checking out  the `actionloop-starter-kit` from the `incubator-openwhisk-devtools` repository
- editing the `Dockerfile` to create the target environment for your language
- rewrite the `launcher.py` in your language
- edit the `compile` script to compile your action in your target language
- write the mandatory tests for your language, adapting the `ActionLoopPythonBasicTests.scala`

Since we need to show the code you have to translate in some language, we picked Python as it is one of the more readable languages, the closer to be real-world `pseudo-code`.

You need to know a bit of Python to understand the sample `launcher.py`, just enough to rewrite it in your target language.

You may need to write some real Python coding to edit the `compile` script, but basic knowledge is enough.

Finally, you do not need to know Scala, even if the tests are embedded in a Scala test, as all you need is to embed your tests in the code.
## Notation

In this tutorial we have either terminal transcripts to show what you need to do at the terminal, or "diffs" to show changes to existing files.

In terminal transcripts, the prefix  `$`  means commands you have to type at the terminal;  the rest are comments (prefixed with `#`) or sample output you should check to verify everything is ok. Generally in a transcript I do not put verbatim output of the terminal as it is generally irrelevant.

When I show changes to existing files, lines without a prefix should be left as is,  lines  with `-` should be removed and lines with  `+` should be added.

## Setup the development directory

So let's start to create our own `actionloop-demo-ruby-2.6`. First, check out the `devtools` repository to access the starter kit, then move it in your home directory to work on it.

```
$ git clone https://github.com/apache/incubator-openwhisk-devtools
$ mv incubator-openwhisk-devtools/actionloop-starter-kit ~/actionloop-demo-ruby-v2.6
```

Now we take the directory `python3.7` and rename it to `ruby2.6`; we also fix a couple of references, in order to give a name to our new runtime.

```
$ cd actionloop-demo-ruby-v2.6
$ mv python3.7 ruby2.6
$ sed -i.bak -e 's/python3.7/ruby2.6/' settings.gradle
$ sed -i.bak -e 's/actionloop-demo-python-v3.7/actionloop-demo-ruby-v2.6/' ruby2.6/build.gradle
```

Let's check everything is fine building the image.

```
# building the image
$ ./gradlew distDocker
... omissis ...
BUILD SUCCESSFUL in 1s
2 actionable tasks: 2 executed
# checking the image is available
$ docker images actionloop-demo-ruby-v2.6
REPOSITORY                  TAG                 IMAGE ID            CREATED             SIZE
actionloop-demo-ruby-v2.6   latest              df3e77c9cd8f        8 days ago          94MB
```

So we have built a new image `actionloop-demo-ruby-v2.6`. However, aside from the renaming, internally is still the old Python. We will change it to support Ruby in the rest of the tutorial.

## Preparing the Docker environment

The `Dockerfile` has the task of preparing an environment for executing our actions, so we have to find (or build and deploy on Docker Hub) an image suitable to run our target programming language. We use multistage Docker build to "extract" the *ActionLoop* proxy from the Docker image.

For the purposes of this tutorial, you should use the `/bin/proxy` binary you can find in the `openwhisk/actionlooop-v2` image on Docker Hub.

In your runtime image, you have then copied the ActionLoop proxy, the `compile` and the file `launcher.rb` we are going to write.

Let's rename the launcher and fix the `Dockerfile` to create the environment for running Ruby.

```
$ mv ruby2.6/lib/launcher.py ruby2.6/lib/launcher.rb
```

Now let's edit the `ruby2.6/Dockerfile` to use, instead of the python image, the official ruby image on Docker Hub, and add out files:

```
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

Note that:

1. You changed the base action to use a Ruby image
1. You included the ruby launcher instead of the python one
1. Since the Docker image we picked is a Ruby one, and the `compile` script is still a python script, we had to add it too

Of course, you can avoid having to add python inside, but you may need to rewrite the entire `compile` in Ruby.  You may decide to translate the entire `compile` in your target language, but this is not the focus of this tutorial.

## Implementing the ActionLoop protocol

Now you have to convert the `launcher.py` in your programming language.  Let's recap the ActionLoop protocol.

### What the launcher should do

The launcher must imports your function first. It is the job of the `compile` script to make the function available to the launcher, as we will see in the next paragraph.

Once the function is imported, it opens the file descriptor 3 for output then reads the standard input line by line.

For each line, it parses the input in JSON and expects it to be a JSON object (not an array nor a scalar).

In this object, the key `value` is the payload to be passed to your functions. All the other keys will be passed as environment variables, uppercases and with prefix `__OW_`.

Finally, your function is invoked with the payload. Once the function returns the result, standard out and standard error is flushed. The result is encoded in JSON, ensuring it is only one line and it is terminated with one newline and it is written in file descriptor 3.

Then the loop starts again. That's it.

### Converting `launcher.py` in `launcher.rb`

Now, let's see the protocol in code, converting the Python launcher in Ruby.

The compilation script as we will see later will ensure the sources are ready for the launcher.

You are free to decide where your source action is. I generally ensure that the starting point is a file named like `main__.rb`, with the two underscore final, as those names are pretty unusual to ensure uniqueness.

Let's skip the imports as they are not interesting. So in Python, the first (significant) line is:

```
# now import the action as process input/output
from main__ import main as main
```

In Ruby, this translates in:

```
# requiring user's action code
require "./main__"
```

Now, we open the file descriptor 3, as the proxy will invoke the action with this descriptor attached to a pipe where it can read the results. In Python:

```
out = fdopen(3, "wb")
```

becomes:

```
out = IO.new(3)
```

Let's read in Python line by line:

```
while True:
  line = stdin.readline()
  if not line: break
  # ...continue...
```

becomes:

```
while true
  # JSON arguments get passed via STDIN
  line = STDIN.gets()
  break unless line
  # ...continue...
end
```

Now, you have to read and parse in JSON one line, then extract the payload and set the other values as environment variables:

```
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

translated:

```
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

We are at the point of invoking our functions. You should capture exceptions and produce an `{"error": <result> }` if something goes wrong. In Python:

```
  # ... continuing ...
  res = {}
  try:
    res = main(payload)
  except Exception as ex:
    print(traceback.format_exc(), file=stderr)
    res = {"error": str(ex)}
  # ... continue ...
```

Translated in Ruby:

```
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

Finally, you flush standard out and standard error and write the result back in file descriptor 3. In Python:

```
  out.write(json.dumps(res, ensure_ascii=False).encode('utf-8'))
  out.write(b'\n')
  stdout.flush()
  stderr.flush()
  out.flush()
```

That becomes in Ruby:

```
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

So let's go first examine how ActionLoop handles file upload, then what the provided compilation script does for Python, and finally  we will see how to modify the existing `compile` for Python to work for Ruby.

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
