Blackbox Actions
================

1. Download and install the OpenWhisk CLI
2. Install OpenWhisk Docker action skeleton.
3. Add user code
4. Build image
5. Push image
6. Test out action with CLI

The script `buildAndPush.sh` is provided for your convenience. The following command sequence
runs the included example Docker action container using OpenWhisk.

```
# install dockerSkeleton with example
wsk sdk install docker

# change working directory
cd dockerSkeleton

# build/push, argument is your docker hub user name and a valid docker image name
./buildAndPush <dockerhub username>/whiskexample

# create docker action
wsk action create dockerSkeletonExample --docker <dockerhub username>/whiskExample

# invoke created action
wsk action invoke dockerSkeletonExample --blocking
```

The executable file must be located in the `/action` folder.
The name of the executable must be `/action/exec` and can be any file with executable permissions.
The sample docker action runs `example.c` by copying and building the source inside the container
as `/action/exec` (see `Dockerfile` lines 7 and 14).
