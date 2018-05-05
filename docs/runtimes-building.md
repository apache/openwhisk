<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements.  See the NOTICE file distributed with this work for additional
# information regarding copyright ownership.  The ASF licenses this file to you
# under the Apache License, Version 2.0 (the # "License"); you may not use this
# file except in compliance with the License.  You may obtain a copy of the License
# at:
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations under the License.
#
-->

# Working with Runtimes locally

This document is designed to be applied to the various
`incubator-openwhisk-runtimes-*` repositories as they are converted to
multi-architecture build. The multi-architecture build supports building Docker
multi-architecture manifest lists across (currently) amd64, Power 64
(little-endian), and System Z (s390x) architectures.  The framework is designed
to extend to other chips, including the Arm64 chipset.

You can tell that a runtime has been converted to a multi-architecture build
by referring to its root `README.md` file.

## Reading this document

For this document, we will assume an environment variable `ACTION_KIND` has
been set to the directory name of an action in the runtime repository on which
work is being done (i.e. set to `nodejs6Action` while working in
`incubator-openwhisk-runtime-nodejs`).

## Building a single-architecture Docker image locally

To build a single-architecture image locally on the local machine, execute

```
docker_local_json='{"local":null}' ./gradlew core:$ACTION_KIND:dockerBuildImage
```

(For the curious at heart, the assignment of `docker_local_json` serves to
document to the script that the command is to build a single-architecture image
on the default docker connection).

To check on what Docker images will be produced, look in the `settings.json` file in
the runtime repository (look for `gradle.ext.dockerBuildProjects =`).  The
default image prefix is currently 'whisk'.

## Building a multi-architecture Docker image

Multi-architecture builds work be repeating build steps on independent docker
instances for each architecture. To ensure security, it's necessary to set up
the docker instances to use TLS and to have the TLS client certificates (
`ca.pem`, `cert.pem` and `key.pem`) available in some directory on the local
machine.  For details on setting up docker with TLS see [the official docker
documentation](https://docs.docker.com/engine/security/https/).

To build a multi-architecture image requires providing more sophisticated
JSON defining the build docker environment for each architecture.  While this
could be done on the command line (as above), it is generally easier to create
a `docker-local.json` file in the root directory of the runtime.  (Don't
worry, it's `.gitignore`d and won't find its way into the open.)

Here's a (sanitized) example of a docker-local.json file:

```
{
    "amd64": null,
    "ppc64le": {
        "url": "https://ppc64le.example.com:2376",
        "certPath": "./tls/ppc64le"
    },
    "s390x": {
        "url": "https://s390x.example.com:2376",
        "certPath": "./tls/s390x"
    }
}
```

In this example `amd64` is local/default (`null`), while `ppc64le` and `s390x`
both use remote docker instances to build.  The certificates for the remote
instances are stored in subdirectories of `tls` in the working directory.
(`./tls` is also `.gitignore`d).

Also, the names of the individual configuration objects do not matter.  While
it is useful to name them after the target architecture, in reality the build
script queries the individual dockers to determine their architectures.  The
names _are_, however, used to construct image tag names in docker, so it is
necessary that they be unique and legitimate symbols for docker tags.

Once the `docker-local.json` file is set up, building is a matter of executing

```
./gradlew core:$ACTION_KIND:dockerBuildImage
```

By default, the images built will be tagged `latest-<arch>`, for example
`latest-s390x`.

## Pushing images (not to be confused with "Pushing Tin")

Now we're ready for the big leagues, registering images that we build with
a docker registry, either Docker Hub or an enterprise/private registry.

To configure the docker registry, set these environment variables:

- `DOCKER_REGISTRY` - the name of the registry (i.e. `docker.io`)
- `DOCKER_USER`
- `DOCKER_PASSWORD`
- `DOCKER_EMAIL`

One configured, pushing images is a matter of setting (or substituting) a
`user_prefix` and executing

```
./gradlew core:$ACTION_KIND:dockerPushImage -PdockerImagePrefix=$user_prefix
```

This pushes *individual* images tagged for the separate architectures.

Note: the `user_prefix` will default to `openwhisk` if not provided.

## Putting the multi-architecture manifest list

At this point, putting a multi-architecture manifest list that can be pulled
by any supported architecture is simply a matter of executing

```
./gradlew core:$ACTION_KIND:dockerPushImage -PdockerImagePrefix=$user_prefix
```

The daring can feel free to start with this command which, if properly
configured, will run all preliminaries.

## Using an action in your OpenWhisk environment

To use a locally built image in a running OpenWhisk environment, execute

```
wsk action update myAction myAction.js --docker $DOCKER_REGISTRY/$user_prefix/$image_name
```

where the action name and contect (e.g. myAction.js) are decided by you and
appropriate, and the `$image_name` is that which was built above.

