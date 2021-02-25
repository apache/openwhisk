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

Apache OpenWhisk supports using custom Docker images as the action runtime. Custom runtimes images can either have the action source files built-in or injected dynamically by the platform during initialisation.

Building custom runtime images is a common solution to the issue of having external application dependencies too large to deploy, due to the action size limit (48MB), e.g. machine learning libraries.

### Usage

The [Apache OpenWhisk CLI](https://github.com/apache/openwhisk-cli) has a `--docker` configuration parameter to set a custom runtime for an action.

```
wsk action create <ACTION_NAME> --docker <IMAGE> source.js
```

*`<IMAGE>` must be an image name for a public Docker image on [Docker Hub](https://hub.docker.com/search?q=&type=image).*

The `--docker` flag can also be used without providing additional source or archive files for an action.

```
wsk action create <ACTION_NAME> --docker <IMAGE>
```

In this scenario, action source code will not be injected into the runtime during cold-start initialisation. The runtime container must handle the platform invocation requests directly.

### Restrictions

- Custom runtime images must implement the [Action interface](https://github.com/apache/openwhisk/blob/master/docs/actions-new.md#runtime-general-requirements). This is the [protocol used by the platform](https://github.com/apache/openwhisk/blob/master/docs/actions-new.md#action-interface) to pass invocation requests to the runtime containers. Containers are expected to expose a HTTP server (running on port 8080) with `/init` and `/run` endpoints.
- Custom runtime images must be available on [Docker Hub](https://hub.docker.com/search?q=&type=image). Docker Hub is the only container registry currently supported. This means all custom runtime images will need to be publicly available.
- Custom runtime images will be pulled from Docker Hub into the local platform registry upon the first invocation. This can lead to longer cold-start times on the first invocation with a new or updated image. Once images have been pulled down, they are cached locally.

### Image Refresh Behaviour

Custom runtimes images should be versioned using explicit [image tags](https://docs.docker.com/engine/reference/commandline/tag/) where possible.

When an image identifier has the `latest` tag (or has no explicit tag), creating new runtime containers from a custom image will always result in an image refresh check against the registry. Pulling an image may fail due to a network interruption or Docker Hub outage. Explicitly tagged images allow the system to gracefully recover by using locally cached images, making it much more resilient against external issues.

The "latest" tag should only be used for rapid prototyping, where guaranteeing the latest code state is more important than runtime stability at scale.

### Existing Runtime Images

Apache OpenWhisk publishes all the [existing runtime images](https://hub.docker.com/u/openwhisk) on Docker Hub. This makes it simple to extend an existing runtimes with additional libraries or native dependencies. Public runtimes images can be used as base images in new runtime images.

Here are some of the more common runtime images...

- `openwhisk/action-nodejs-v10` - [Node.js 10](https://hub.docker.com/r/openwhisk/action-nodejs-v10) ([Source](https://github.com/apache/openwhisk-runtime-nodejs/blob/master/core/nodejs10Action/Dockerfile))
- `openwhisk/python3action` - [Python 3](https://hub.docker.com/r/openwhisk/python3action) ([Source](https://github.com/apache/openwhisk-runtime-python/blob/master/core/pythonAction/Dockerfile))
- `openwhisk/java8action` - [Java 8](https://hub.docker.com/r/openwhisk/java8action) ([Source](https://github.com/apache/openwhisk-runtime-java/blob/master/core/java8/Dockerfile))
- `openwhisk/action-swift-v4.2` - [Swift 4.2](https://hub.docker.com/r/openwhisk/action-swift-v4.2) ([Source](https://github.com/apache/openwhisk-runtime-swift/blob/master/core/swift42Action/Dockerfile))
- `openwhisk/action-php-v7.4` - [PHP 7.4](https://hub.docker.com/r/openwhisk/action-php-v7.4) ([Source](https://github.com/apache/openwhisk-runtime-php/blob/master/core/php7.4Action/Dockerfile))
- `openwhisk/action-ruby-v2.5` - [Ruby 2.5](https://hub.docker.com/r/openwhisk/action-ruby-v2.5) ([Source](https://github.com/apache/openwhisk-runtime-ruby/blob/master/core/ruby2.5Action/Dockerfile))

## Extending Existing Runtimes

If you want to use extra libraries in an action that can't be deployed (due to the action size limit), building a custom runtime with a project image runtime base image is an easy way to handle this.

By using an existing language runtime image as the base image, the container will already be set up to handle platform invocation requests for that language. This means the image build file only needs to contain commands to install those extra libraries or dependencies

Here are examples for Node.js and Python using this approach to provide large libraries in the runtime.

### Node.js

[Tensorflow.js](https://www.tensorflow.org/js/) is a JavaScript implementation of [TensorFlow](https://www.tensorflow.org/), the open-source Machine Learning library from Google. This project also comes with a [Node.js backend driver](https://github.com/tensorflow/tfjs-node) to run the project on CPU or GPU devices in the Node.js runtime.

Both the core library and CPU backend driver for Node.js (`tfjs` and `tfjs-node`) can be installed as normal NPM packages. Unfortunately, it is not possible to deploy these libraries in a zip file to the Node.js runtime in Apache OpenWhisk due to the size of the library and its dependencies. The `tfjs-node` library has a native dependency which is over 170MB.

Instead, we can build a custom runtime which extends the project's Node.js runtime image and runs `npm install` during the container build process. These libraries will then be pre-installed into the runtime and can be excluded from the deployment archive.

- Create a `Dockerfile` with the following contents:

```
FROM openwhisk/action-nodejs-v10:latest

RUN npm install @tensorflow/tfjs-node
```

- Build the Docker image.

```
docker build -t action-nodejs-v10:tf-js .
```

- Tag the Docker image with your Docker Hub username.

```
docker tag action-nodejs-v10:tf-js <USER_NAME>/action-nodejs-v10:tf-js
```

- Push the Docker image to Docker Hub.

```
docker push <USER_NAME>/action-nodejs-v10:tf-js
```

- Create a new Apache OpenWhisk action with the following source code:

```javascript
const tf = require('@tensorflow/tfjs-node')

const main = () => {
  return { tf: tf.version }
}
```

```
wsk action create tfjs --docker <USER_NAME>/action-nodejs-v10:tf-js action.js
```

- Invoking the action should return the TensorFlow.js libraries versions available in the runtime.

```
wsk action invoke tfjs --result
```

```
{
    "tf": {
        "tfjs": "1.0.4",
        "tfjs-converter": "1.0.4",
        "tfjs-core": "1.0.4",
        "tfjs-data": "1.0.4",
        "tfjs-layers": "1.0.4",
        "tfjs-node": "1.0.3"
    }
}
```

### Python

Python is a popular language for machine learning and data science due to availability of libraries like [numpy](http://www.numpy.org/).

Python libraries can be [imported into the Python runtime](https://github.com/apache/openwhisk/blob/master/docs/actions-python.md#packaging-python-actions-with-a-virtual-environment-in-zip-files) in Apache OpenWhisk by including a `virtualenv` folder in the deployment archive. This approach does not work when the deployment archive would be larger than the action size limit (48MB).

Instead, we can build a custom runtime which extends the project's Python runtime image and runs `pip install` during the container build process. These libraries will then be pre-installed into the runtime and can be excluded from the deployment archive.

- Create a `Dockerfile` with the following contents:

```
FROM openwhisk/python3action:latest

RUN apk add --update py-pip
RUN pip install numpy
```

- Build the Docker image.

```
docker build -t python3action:ml-libs .
```

- Tag the Docker image with your Docker Hub username.

```
docker tag python3action:ml-libs <USER_NAME>/python3action:ml-libs
```

- Push the Docker image to Docker Hub.

```
docker push <USER_NAME>/python3action:ml-libs
```

- Create a new Apache OpenWhisk action with the following source code:

```python
import numpy

def main(params):
    return {
        "numpy": numpy.__version__
    }
```

```
wsk action create ml-libs --docker <USER_NAME>/python3action:ml-libs action.py
```

- Invoking the action should return the TensorFlow.js library versions available in the runtime.

```
wsk action invoke ml-libs --result
```

```
{
    "numpy": "1.16.2"
}
```

## Creating native actions

Docker support can also be used to run any executable file (from static binaries to shell scripts) on the platform. Executable files need to use the `openwhisk/dockerskeleton` [runtime image](https://github.com/apache/openwhisk-runtime-docker). Native actions can be created using the `--native` CLI flag, rather than explicitly specifying `dockerskeleton` as the runtime image name.

### Usage

```
wsk action create my-action --native source.sh
wsk action create my-action --native archive.zip
```

Executables can either be text or binary files. Text-based executable files (e.g. shell scripts) are passed directly as the action source files. Binary files (e.g. C programs) must be named `exec` and packaged into a zip archive.

Native action source files must be executable within the  `openwhisk/dockerskeleton` [runtime image](https://github.com/apache/openwhisk-runtime-docker). This means being compiled for the correct platform architecture, linking to the correct dynamic libraries  and using pre-installed external dependencies.

When an invocation request is received by the runtime container, the native action file will be executed until the process exits. Action invocation parameters will be passed as a JSON string to `stdin`.

When the process ends, the last line of text output to `stdout` will be parsed as the action result. This must contain a text string with a JSON object. All other text lines written to `stdout` will be treated as logging output and returned into the response logs for the activation.

### Example (Shell Script)

- Create a shell script called `script.sh` with the following contents.

```
#!/bin/bash
ARGS=$@
NAME=`echo "$ARGS" | jq -r '."name"'`
DATE=`date`
echo "{ \"message\": \"Hello $NAME! It is $DATE.\" }"
```

- Create an action from this shell script.

```
 wsk action create bash script.sh --native
```

- Invoke the action with the `name` parameter.

```
wsk action invoke bash --result --param name James
```

```
{
    "message": "Hello James! It is Thu Apr 18 15:24:23 UTC 2019."
}
```

### Example (Static C Binary)

- Create a C source file called `main.c` with the following contents:

```c
#include <stdio.h>
int main(int argc, char *argv[]) {
    printf("This is an example log message from an arbitrary C program!\n");
    printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s }",
           (argc == 1) ? "undefined" : argv[1]);
}
```

- Create a shell script (`build.sh`) with the following contents:

```
#!/bin/bash
apk add gcc libc-dev

gcc main.c -o exec
```

- Make the build script executable.

```
chmod 755 build.sh
```

- Compile the C binary using the `dockerskeleton` image and the build script.

```
docker run -it -v $PWD:/action/ -w /action/ openwhisk/dockerskeleton ./build.sh
```

- Add the binary to a zip file.

```
zip -r action.zip exec
```

- Create an action from the zip file containing the binary.

```
 wsk action create c-binary action.zip --native
```

- Invoke the action with the `name` parameter.

```
wsk action invoke c-binary --result --param name James
```

```
{
    "args": {
        "name": "James"
    },
    "msg": "Hello from arbitrary C program!"
}
```
