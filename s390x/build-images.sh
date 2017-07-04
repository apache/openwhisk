#!/bin/bash

#
#  Note, the Dockerfile is expected to have a label in the form of
#  "#LABEL mylabel" to set the label
#
#  TODO:  Re-write this to take advantage of the "LABEL" dockerfile
#         directive to embed intended label in image metadata.  Use
#         'jq' to extract it.
#
#  TODO:  Add syntax validation of label before docker has to choke on it.

REPOSITORY=${REPOSITORY:-jpspring/s390x-openwhisk}

cd "$( dirname "${BASH_SOURCE[0]}" )" || exit

process_dockerfile() {
  label=$(grep '^#LABEL ' "$1" | sed -n 's/^#LABEL \(.*\)$/\1/p')
  [ -d "./$label" ] && dir="./$label" || dir="."
  docker build -t "$REPOSITORY:$label" -f "$1" "$dir" || {
    echo Failure building $1; exit 1
  }
  docker push "jpspring/s390x-openwhisk:$label"

  for i in "$(dirname $1)"/test_*; do
    if [[ -x "$i" ]]; then eval "$i"; fi
  done
}

if [ "$#" -eq 0 ]; then
  for i in */Dockerfile; do process_dockerfile "$i"; done
else
  while [ "$#" -ne 0 ]; do process_dockerfile "$1/Dockerfile"; shift; done
fi
