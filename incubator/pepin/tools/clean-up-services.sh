#!/bin/bash
kn service list -o 'jsonpath={range .items[*]}{.metadata.name}{"\n"}{end}' | egrep '^ow-' | xargs -I{} kn service delete {}