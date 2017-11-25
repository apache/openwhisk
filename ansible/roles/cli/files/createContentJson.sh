#!/bin/bash

set +x
set -e

get_compressed_name() {
  local os=$1
  local arch=$2
  local product_name=$3

  if [ $arch = amd64 ]; then
    comp_name="$product_name-$os";
  elif [ $arch = 386 ]; then
    comp_name="$product_name-$os-32bit";
  else
    comp_name="$product_name-$os-$arch";
  fi

  echo $comp_name;
};

get_binary_path() {
    local comp_name=$1
    local os=$2
    local arch=$3

    if [ $os = "linux" ]; then
        comp_name="$comp_name.tgz"
    else
        comp_name="$comp_name.zip"
    fi
    echo $os/$arch/$comp_name;
};

create_cli_packages() {
  local dirIndex="{\"cli\":{"

  for platform in $platforms; do
    dirIndex="$dirIndex\"$platform\":{"

    for arch in $archs; do
      comp_name=$(get_compressed_name $platform $arch $zip_name)
      comp_path=$(get_binary_path $comp_name $platform $arch)

      if [ $arch = $default_arch ]; then
          dirIndex="$dirIndex\"default\":{\"path\":\"$comp_path\"},";
      fi

      dirIndex="$dirIndex\"$arch\":{\"path\":\"$comp_path\"},";
    done

    dirIndex="$(echo $dirIndex | rev | cut -c2- | rev)"
    dirIndex="$dirIndex},";
  done

  dirIndex="$(echo $dirIndex | rev | cut -c2- | rev)"
  dirIndex="$dirIndex}}"

  mkdir -p "${PATH_CONTENT_JSON}"
  echo $dirIndex > "${PATH_CONTENT_JSON}/content.json"
};

archs="386 amd64";
default_arch="amd64"

PATH_CONTENT_JSON=$1
BINARY_TAG=$2
platforms=$3
zip_name=$4
create_cli_packages
