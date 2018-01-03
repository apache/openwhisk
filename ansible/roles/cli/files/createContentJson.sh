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

  #  I'm making a nasty assumption here that it's sorted by platforms
  old_platform=''  # Not really necessary, but here to be explicit & transparent
  for platform_arch in $platforms; do

    IFS="," read platform arch <<<"${platform_arch}"

    #  Control-break processing for platform changes
    if [ "$platform" != "$old_platform" ]; then
      if [ "$old_platform" != "" ]; then
        dirIndex="${dirIndex%','}" # Remove trailing comma
        dirIndex+="},"             # And replace with end-brace comma
      fi
      dirIndex+="\"$platform\":{"
    fi
    old_platform=$platform

    comp_name=$(get_compressed_name $platform $arch $zip_name)
    comp_path=$(get_binary_path $comp_name $platform $arch)

    if [ $arch = $default_arch ]; then
      dirIndex+="\"default\":{\"path\":\"$comp_path\"},";
    fi

    dirIndex+="\"$arch\":{\"path\":\"$comp_path\"},";
  done

  #dirIndex="$(echo $dirIndex | rev | cut -c2- | rev)"
  dirIndex="${dirIndex%','}"   # Remove trailing comma
  dirIndex+="}}}"              # And replace with end-braces

  mkdir -p "${PATH_CONTENT_JSON}"
  echo $dirIndex > "${PATH_CONTENT_JSON}/content.json"
};

default_arch="amd64"

PATH_CONTENT_JSON=$1
# 'shellcheck' pointed out that BINARY_TAG is unused.  I'm leaving it for now
# because the future of content.json is in flux.
BINARY_TAG=$2
platforms=$3
zip_name=$4
create_cli_packages
