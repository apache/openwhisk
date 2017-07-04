#!/bin/bash

#BUILD_ARGS=--assertions --no-swift-stdlib-assertions       --release --build-dir=/home/swiftbuild/build --build-subdir=a --lldb --llbuild --libdispatch  --foundation --xctest --swiftpm
#BUILD_IMPL_ARGS=--swift-enable-ast-verifier=0       --build-swift-static-stdlib       --build-swift-static-sdk-overlay       --build-swift-stdlib-unittest-extra       --install-destdir=/home/swiftbuild/install       --install-swift       --swift-install-components=autolink-driver;compiler;clang-builtin-headers;stdlib;swift-remote-mirror;sdk-overlay;license --install-lldb --install-llbuild --install-libdispatch --install-foundation --install-swiftpm --install-xctest

docker run -t swift-build bash -c ' ./src/swift/utils/build-script \
    $BUILD_ARGS --test --validation-test --long-test --lit-args=-v \
    -- $BUILD_IMPL_ARGS --skip-test-lldb'
