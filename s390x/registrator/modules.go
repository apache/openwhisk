package main

import (
	_ "github.com/gliderlabs/registrator/consul"
	_ "github.com/gliderlabs/registrator/consulkv"
	_ "github.com/gliderlabs/registrator/etcd"
	_ "github.com/gliderlabs/registrator/skydns2"
	_ "github.com/gliderlabs/registrator/zookeeper"
)
