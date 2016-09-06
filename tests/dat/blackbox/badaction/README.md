A docker action that can manipulates `/init` and `/run` in different ways including
- not responding
- aborting and terminating the container

The action overrides the [common action proxy runner](../../../core/actionProxy/actionproxy.py) with programmable `init` and `run` methods.
These containers are used in [Docker container tests](../../src/actionContainers/DockerExampleContainerTests.scala).
