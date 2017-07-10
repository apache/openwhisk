#!/bin/bash

shopt -s lastpipe

DOCKER_REGISTRY=${DOCKER_REGISTRY:-docker.xanophis.com}

cd "$( dirname "${BASH_SOURCE[0]}" )" || exit

arch=$(docker info 2>/dev/null | sed -n 's/Architecture: \(.*\)/\1/p')
echo "Processing for $arch"

process_dockerfile() {
  local tmpfile dockerfile

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

  #label=$(grep '^#LABEL ' "$dockerfile" | sed -n 's/^#LABEL \(.*\)$/\1/p')
  docker build -t "t_$$" -f "$dockerfile" "$1" || {
    echo Failure building $1; exit 1
  }

  #  TODO:  Fail gracefully if the LABELs weren't set up properly
  docker inspect "t_$$" \
    | jq -r '.[].Config.Labels.image_name,.[].Config.Labels.tags' \
    | { read image_name; IFS=',' read -a tags; }

  echo "image_name=" $image_name
  echo "tags=" "${tags[@]}"

  for i in "$(dirname $1)"/test_*; do
    if [[ -x "$i" ]]; then eval "$i"; fi
  done

  for tag in "${tags[@]}"; do
    docker tag "t_$$" "$DOCKER_REGISTRY/$arch/$image_name:$tag"
    docker push "$DOCKER_REGISTRY/$arch/$image_name:$tag"
  done

  docker rmi "t_$$"  # Remove the temporary tag
  [ -n "$tmpfile" ] && rm "$tmpfile"
}

if [ "$#" -eq 0 ]; then
  compgen -G '*/Dockerfile*' | xargs dirname | uniq | \
  while read i; do process_dockerfile "$i"; done
else
  while [ "$#" -ne 0 ]; do [ -d "$1" ] && process_dockerfile "$1"; shift; done
fi
