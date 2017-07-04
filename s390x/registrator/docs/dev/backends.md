# Contributing Backends

As you can see by either the Consul or etcd source files, writing a new registry backend is easy. Just follow the example set by those two. It boils down to writing an object that implements this interface:
```
	type RegistryAdapter interface {
		Ping() error
		Register(service *Service) error
		Deregister(service *Service) error
		Refresh(service *Service) error
	}
```
The `Service` struct looks like this:
```
type Service struct {
	ID    string
	Name  string
	Port  int
	IP    string
	Tags  []string
	Attrs map[string]string
	TTL   int
	...
}
```
Then add a factory which accepts a uri and returns the registry adapter, and register that factory with the bridge like `bridge.Register(new(Factory), "<backend_name>")`.
