#LABEL xenial-gold

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

#  Using 'zesty' because of clang-4.0 access.  I may try to get a backport to
#  xenial forward, but that's yet one more thing to do...
FROM s390x/ubuntu:xenial

#  Debian distros do not include the 'gold' linker by default, so instead
#  we must build and install it from scratch.
RUN apt-get update \
 && apt-get -y install --no-install-recommends \
     bison ca-certificates curl flex g++ make texinfo

RUN mkdir /binutils && cd /binutils \
 && curl -L http://ftp.gnu.org/gnu/binutils/binutils-2.27.tar.gz \
        | tar zfx - --strip-components=1 \
 && ./configure --enable-gold \
 && make && make install \
 && cd / && rm -rf /binutils

#  The Core Foundation library expects these libraries to be installed on the
#  system.  Although the files are included in the downloaded library, this
#  may be the path of least resistance to make them available to the platform.
RUN mkdir -p /blocksruntime && cd /blocksruntime \
   && curl -L https://api.github.com/repos/mackyle/blocksruntime/tarball/master \
      | tar zxf - --strip-components=1 \
   && ./buildlib && ./installlib \
   && cd / && rm -rf /blocksruntime

#  Add common tools and libraries for the Clang/Swift crowd
RUN apt-get -y install --no-install-recommends \
      clang-3.9 python2.7 libcurl4-openssl-dev libssl-dev 

RUN update-alternatives --install /usr/bin/clang++ clang++ /usr/bin/clang++-3.9 20 \
 && update-alternatives --install /usr/bin/g++ g++ /usr/bin/clang++-3.9 20 \
 && update-alternatives --install /usr/bin/clang clang /usr/bin/clang-3.9 20 \
 && update-alternatives --install /usr/bin/gcc gcc /usr/bin/clang-3.9 20

#  Clean up apt leftovers to save space
RUN apt-get clean && rm -rf /var/lib/apt/lists/*
