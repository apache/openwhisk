package pool

import "git.corp.adobe.com/bladerunner/pepin/internal/pkg/datastore"
import "net/url"

type Resource struct {
	Provider    string
	Name        string
	Url         url.URL
	ExternalUrl url.URL
	Kind        string
}

type Action struct {
	Resource
	datastore.ActionMetaData
}

type Pool interface {
	Allocate(metadata datastore.ActionMetaData, routerId string) (<-chan Action, <-chan error)
	Close()
	GC(routerId string)
}

//type Pool struct {
//	Allocate  chan Resource
//	Initialize chan Action
//	Ready chan Action
//}

const (
	LocalStorage = iota
	EtcdStorage
)

type PoolStorageConfig struct {
	Kind int
}
