# Dockerfile for swift actions, overrides and extends ActionRunner from actionProxy
# This Dockerfile is partially based on: https://github.com/swiftdocker/docker-swift/

# Temporarily disable Swift3 action container image build because it causes a build break.
# It has a dependency on https://github.com/IBM-Swift/swift-watson-sdk.git
# which is no more available.
# Just base on a fixed version which is available on public DockerHub

FROM openwhisk/swift3action:03023a1