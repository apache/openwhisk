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

## Creating and invoking Docker actions

With OpenWhisk Docker actions, you can write your actions in any language, bundle larger
or complex dependencies, and tailor the runtime environment to suite your needs.

- As a prerequisite, you must have a Docker Hub account.
  To set up a free Docker ID and account, go to [Docker Hub](https://hub.docker.com).
- The easiest way to get started with a Docker action is to use the OpenWhisk Docker skeleton.
  - You can install the skeleton with the `wsk sdk install docker` CLI command.
  - This is a a Docker image based on [python:3.6.1-alpine](https://hub.docker.com/r/library/python).
  - The skeleton requires an entry point located at `/action/exec` inside the container.
    This may be an executable script or binary compatible with this distribution.
  - The executable receives the input arguments via a single command-line argument string
    which can be deserialized as a `JSON` object.
    It must return a result via `stdout` as a single-line string of serialized `JSON`.
  - You may include any compilation steps or dependencies by modifying the `Dockerfile`
    included in the `dockerSkeleton`.

The instructions that follow show you how to use the OpenWhisk Docker skeleton.

1. Install the Docker skeleton.

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

2. Create the executable. The Docker skeleton includes a C program that you can use as an example.

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
  By convention, the last line of output _must_ be a stringified JSON object which represents the result of
  the action.

3. Build the Docker image and upload it using a supplied script.
  You must first run `docker login` to authenticate, and then run the script with a chosen image name.

  ```
  docker login -u janesmith -p janes_password
  ```
  ```
  cd dockerSkeleton
  ```
  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```

  Notice that part of the example.c file is compiled as part of the Docker image build process,
  so you do not need C compiled on your machine.
  In fact, unless you are compiling the binary on a compatible host machine, it may not run inside
  the container since formats will not match.

  Your Docker container may now be used as an OpenWhisk action.

  ```
  wsk action create example --docker janesmith/blackboxdemo
  ```

  Notice the use of `--docker` when creating an action. Currently all Docker images are assumed
  to be hosted on Docker Hub.

  *Note:* It is considered best-practice for production images to be versioned via docker image tags. The absence of a tag will be treated the same as using the tag "latest", which will guarantee to pull from the registry when creating new containers. That contains the possibility of failing because pulling the image fails. For tagged images however, the system will allow a failing pull and gracefully recover it by using the image that is already locally available, making it much more resilient against Dockerhub outages etc.

  The "latest" tag should therefore only be used for rapid prototyping where guaranteeing the latest code state is more important than runtime stability at scale.

  Please also note that "latest" doesn't mean newest tag, but rather "latest" is an alias for any image built without an explicit tag.

## Invoking a Docker action

Docker actions are invoked as [any other OpenWhisk action](actions.md#the-basics).

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

## Updating a Docker action

To update the Docker action, run buildAndPush.sh again _and_ update your action.
This will upload the latest image to Docker Hub and force the system to create a new container based on the image.
If you do not update the action, then the image is pulled when there are no warm containers available for your action.
A warm container will continue using a previous version of your Docker image,
and any new invocations of the action will continue to use that image unless you run `wsk action update`.
This will indicate to the system that for new invocations it should execute a docker pull to get your new Docker image.

  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```
  ```
  wsk action update example --docker janesmith/blackboxdemo
  ```

**Note:** As noted above, only images with the tag "latest" or no tag will guarantee to be pulled again, even after updating the action. Any other tag might fall back to use the old image for stability reasons.

To force an updated image after an action update, consider using versioned tags on the image.

## Creating native actions

Docker actions accept initialization data via a (zip) file, similar to other actions kinds.
For example, the tutorial above created a binary executable inside the container located at `/action/exec`.
If you copy this file to your local file system and zip it into `exec.zip` then you can use the following
commands to create a docker action which receives the executable as initialization data.

  ```bash
  wsk action create example exec.zip --native
  ```
  which is equivalent to the following command.
  ```bash
  wsk action create example exec.zip --docker openwhisk/dockerskeleton
  ```

Using `--native`, you can see that any executable may be run as an OpenWhisk action.
This includes `bash` scripts, or cross compiled binaries. For the latter, the constraint
is that the binary must be compatible with the `openwhisk/dockerskeleton` image.
