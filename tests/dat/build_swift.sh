#!/bin/bash
set -e

../../tools/build/compile_swift.sh  HelloSwift4 swift:4.1 "-v"
../../tools/build/compile_swift.sh  SwiftyRequest swift:4.1 "-v"
../../tools/build/compile_swift.sh  HelloSwift4Codable swift:4.1 "-v"
