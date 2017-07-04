#
#  Swift's size requires Docker to be configured to provide at least 30GB of
#  storage for the base image.  I may change the process to mount a host
#  directory at some point, but for now having docker manage storage makes
#  the most sense.
#
#  I run this container on an underlying instance with 16GB.  At lower memory
#  levels, it's necessary to single-thread the build ('-j1' option) to prevent
#  memory blow-outs.
#
#  TODO:  Validate size requirements; it seems to have gotten better since
#         I stumbled onto the linux preset to drive build.

#  Using 'zesty' because of clang-4.0 access.  I may try to get a backport to
#  xenial forward, but that's yet one more thing to do...
FROM jpspring/s390x-openwhisk:xenial-gold

#  Tools, libraries and dev packages (includes) needed to run the build
RUN apt-get update && apt-get -y install \
     autoconf automake cmake doxygen libtool \
     libedit-dev libicu-dev libsqlite3-dev libxml2-dev \
     ninja-build pkg-config ncurses-dev ocaml patch \
     python-dev python-sphinx python3 \
     re2c rsync swig uuid-dev

#  At this point, we should have everything in the build environment that is
#  needed to create the library

# ----------------------------------------------------------------------------

#  Create and switch to a build user to manage the "install" options so as
#  not to accidentally do a root install.

RUN adduser --disabled-password --gecos "" swiftbuild
USER swiftbuild
WORKDIR /home/swiftbuild
ENV RELEASE swift-3.1.1-RELEASE

#  Use the github tarball API to limit the amount of space wasted on the image
RUN mkdir ./src && cd ./src \
 && mkdir swift cmark llvm lldb clang llbuild swiftpm \
      swift-corelibs-xctest swift-corelibs-foundation swift-corelibs-libdispatch \
 && curl -L https://api.github.com/repos/apple/swift/tarball/tags/$RELEASE \
      | tar zx -C swift -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-cmark/tarball/tags/$RELEASE \
      | tar zx -C cmark -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-llvm/tarball/tags/$RELEASE \
      | tar zx -C llvm -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-lldb/tarball/tags/$RELEASE \
      | tar zx -C lldb -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-clang/tarball/tags/$RELEASE \
      | tar zx -C clang -f - --strip-components=1  \
 && curl -L https://api.github.com/repos/apple/swift-llbuild/tarball/tags/$RELEASE \
      | tar zx -C llbuild -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-package-manager/tarball/tags/$RELEASE \
      | tar zx -C swiftpm -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-corelibs-xctest/tarball/tags/$RELEASE \
      | tar zx -C swift-corelibs-xctest -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-corelibs-foundation/tarball/tags/$RELEASE \
      | tar zx -C swift-corelibs-foundation -f - --strip-components=1 \
 && curl -L https://api.github.com/repos/apple/swift-corelibs-libdispatch/tarball/tags/$RELEASE \
      | tar zx -C swift-corelibs-libdispatch -f - --strip-components=1

# Stage 1 build -- essentially build the core compiler and Swift
ENV BUILD_ARGS="--assertions --no-swift-stdlib-assertions \
      --release --build-dir=/home/swiftbuild/build --build-subdir=a" \
    BUILD_IMPL_ARGS="--swift-enable-ast-verifier=0 \
      --build-swift-static-stdlib \
      --build-swift-static-sdk-overlay \
      --build-swift-stdlib-unittest-extra \
      --install-destdir=/home/swiftbuild/install \
      --install-swift \
      --swift-install-components=autolink-driver;compiler;clang-builtin-headers;stdlib;swift-remote-mirror;sdk-overlay;license"
RUN ./src/swift/utils/build-script $BUILD_ARGS -- $BUILD_IMPL_ARGS

# Stage 2 build -- build ll libraries
ENV BUILD_ARGS="$BUILD_ARGS --lldb --llbuild"
ENV BUILD_IMPL_ARGS="$BUILD_IMPL_ARGS --install-lldb --install-llbuild"
RUN ./src/swift/utils/build-script $BUILD_ARGS -- $BUILD_IMPL_ARGS

# This is not strictly needed, but it cuts down on a slew of warning messages
# from the the foundation build process
COPY ./swift-corelibs-foundation.diff ./src/
RUN patch -d ./src/swift-corelibs-foundation -p1 < ./src/swift-corelibs-foundation.diff

# Stage 3 build -- add swift libraries
ENV BUILD_ARGS="$BUILD_ARGS --libdispatch  --foundation"
ENV BUILD_IMPL_ARGS="$BUILD_IMPL_ARGS --install-libdispatch --install-foundation"
RUN ./src/swift/utils/build-script $BUILD_ARGS -- $BUILD_IMPL_ARGS

# Stage 4 build -- xctest & swiftpm, which seem to always build anyway, and create install archive
ENV BUILD_ARGS="$BUILD_ARGS --xctest --swiftpm"
ENV BUILD_IMPL_ARGS="$BUILD_IMPL_ARGS --install-swiftpm --install-xctest"
RUN ./src/swift/utils/build-script $BUILD_ARGS -- $BUILD_IMPL_ARGS

# Create installable tarball
#
# TODO:  Put the tarball onto a VOLUME
#
RUN tar -c -z -C ./install -f ./$RELEASE.tar.gz --owner=0 --group=0 usr/
