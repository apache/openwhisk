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

arch=$(docker info 2>/dev/null | sed -n 's/Architecture: \(.*\)/\1/p')
echo "Processing for $arch"

process_dockerfile() {
  local tmpfile, dockerfile

  if [ -f "$1/Dockerfile.$arch" ]; then dockerfile="$1/Dockerfile.$arch"
  elif [ -f "$1/Dockerfile.m4" ]; then
    tmpfile="$(mktemp -tp "$1")"
    echo "...Pre-processing $1/Dockerfile.m4 to $tmpfile"
    m4 -P \
      -D "$(echo "$arch" | tr '[:lower:]' '[:upper:]')" -D "m4_dockerarch=$arch" \
		  "$1/Dockerfile.m4" > $tmpfile
    dockerfile="$tmpfile"
  elif [ -f "$1/Dockerfile" ]; then dockerfile="$1/Dockerfile"
  else exit 1
  fi

  echo "...Processing $dockerfile"

  label=$(grep '^#LABEL ' "$dockerfile" | sed -n 's/^#LABEL \(.*\)$/\1/p')
  [ -d "./$label" ] && dir="./$label" || dir="$1"
  docker build -t "$REPOSITORY:$label" -f "$dockerfile" "$dir" || {
    echo Failure building $1; exit 1
  }
  docker push "$REPOSITORY:$label"

  for i in "$(dirname $1)"/test_*; do
    if [[ -x "$i" ]]; then eval "$i"; fi
  done

  [ -n "$tmpfile" ] && rm "$tmpfile"
}

if [ "$#" -eq 0 ]; then
  compgen -G '*/Dockerfile*' | xargs dirname | uniq | \
  while read i; do process_dockerfile "$i"; done
else
  while [ "$#" -ne 0 ]; do [ -d "$1" ] && process_dockerfile "$1"; shift; done
fi
