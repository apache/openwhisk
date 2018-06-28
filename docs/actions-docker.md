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

Docker actions and native actions run a user-supplied binary in a Docker container.
The binary runs in a Docker image based on [python:3.6.1-alpine](https://hub.docker.com/r/library/python), so the binary must be compatible with this distribution.

The Docker skeleton is a convenient way to build OpenWhisk-compatible Docker images.
You can install the skeleton with the `wsk sdk install docker` CLI command.

The main binary program must be located in `/action/exec` inside the container.
The executable receives the input arguments via a single command-line argument string which can be deserialized as a `JSON` object. It must return a result via `stdout` as a single-line string of serialized `JSON`.

You may include any compilation steps or dependencies by modifying the `Dockerfile` included in the `dockerSkeleton`.

## Creating Docker actions

With OpenWhisk Docker actions, you can write your actions in any language.

Your code is compiled into an executable binary and embedded into a Docker image. The binary program interacts with the system by taking input from `stdin` and replying through `stdout`.

As a prerequisite, you must have a Docker Hub account.  To set up a free Docker ID and account, go to [Docker Hub](https://hub.docker.com).

For the instructions that follow, assume that the Docker user ID is `janesmith` and the password is `janes_password`.  Assuming that the CLI is already set up, three steps are required to set up a custom binary for use by OpenWhisk. After that, the uploaded Docker image can be used as an action.

1. Download the Docker skeleton. You can download it by using the CLI as follows:

  ```
  wsk sdk install docker
  ```
  ```
  The Docker skeleton is now installed at the current directory.
  ```

  ```
  $ ls dockerSkeleton/
  ```
  ```
  Dockerfile      README.md       buildAndPush.sh example.c
  ```

  The skeleton is a Docker container template where you can inject your code in the form of custom binaries.

2. Set up your custom binary in the blackbox skeleton. The skeleton already includes a C program that you can use.

  ```
  cat dockerSkeleton/example.c
  ```
  ```c
  #include <stdio.h>
  int main(int argc, char *argv[]) {
      printf("This is an example log message from an arbitrary C program!\n");
      printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s }",
             (argc == 1) ? "undefined" : argv[1]);
  }
  ```

  You can modify this file as needed, or, add additional code and dependencies to the Docker image.
  In case of the latter, you may need to tweak the `Dockerfile` as necessary to build your executable.
  The binary must be located inside the container at `/action/exec`.

  The executable receives a single argument from the command line. It is a string serialization of the JSON
  object representing the arguments to the action. The program may log to `stdout` or `stderr`.
  By convention, the last line of output _must_ be a stringified JSON object which represents the result of the action.

3. Build the Docker image and upload it using a supplied script. You must first run `docker login` to authenticate, and then run the script with a chosen image name.

  ```
  docker login -u janesmith -p janes_password
  ```
  ```
  cd dockerSkeleton
  ```
  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```

  Notice that part of the example.c file is compiled as part of the Docker image build process, so you do not need C compiled on your machine.
  In fact, unless you are compiling the binary on a compatible host machine, it may not run inside the container since formats will not match.

  Your Docker container may now be used as an OpenWhisk action.


  ```
  wsk action create example --docker janesmith/blackboxdemo
  ```

  Notice the use of `--docker` when creating an action. Currently all Docker images are assumed to be hosted on Docker Hub.
  The action may be invoked as any other OpenWhisk action.

  ```
  wsk action invoke --result example --param payload Rey
  ```
  ```json
  {
      "args": {
          "payload": "Rey"
      },
      "msg": "Hello from arbitrary C program!"
  }
  ```

  To update the Docker action, run buildAndPush.sh to upload the latest image to Docker Hub. This will allow the system to pull your new Docker image the next time it runs the code for your action.
  If there are no warm containers any new invocations will use the new Docker image.
  However, if there is a warm container using a previous version of your Docker image, any new invocations will continue to use that image unless you run `wsk action update`. This will indicate to the system that for new invocations it should execute a docker pull to get your new Docker image.

  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```
  ```
  wsk action update example --docker janesmith/blackboxdemo
  ```

  You can find more information about creating Docker actions in the [References](./reference.md#docker-actions) section.

  *Note:* Previous version of the CLI supported `--docker` without a parameter and the image name was a positional argument.
  In order to allow Docker actions to accept initialization data via a (zip) file, similar to other actions kinds, we have
  normalized the user experience for Docker actions so that a positional argument if present must be a file (e.g., a zip file)
  instead. The image name must be specified following the `--docker` option. Furthermore, due to user feedback, we have added
  `--native` as shorthand for `--docker openwhisk/dockerskeleton` so that executables that run inside the standard Docker action
  SDK are more convenient to create and deploy.

  For example, the tutorial above created a binary executable inside the container locate at `/action/exec`. If you copy this file
  to your local file system and zip it into `exec.zip` then you can use the following commands to create a docker action which receives
  the executable as initialization data.

  ```bash
  wsk action create example exec.zip --native
  ```
  which is equivalent to the following command.
  ```bash
  wsk action create example exec.zip --docker openwhisk/dockerskeleton
  ```

## Creating native actions

Using `--native`, you can see that any executable may be run as an OpenWhisk action. This includes `bash` scripts,
or cross compiled binaries. For the latter, the constraint is that the binary must be compatible with the
`openwhisk/dockerskeleton` image.
