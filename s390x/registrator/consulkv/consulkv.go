package consul

import (
	"log"
	"net"
	"net/url"
	"strconv"
	"strings"

	"github.com/gliderlabs/registrator/bridge"
	consulapi "github.com/hashicorp/consul/api"
)

func init() {
	f := new(Factory)
	bridge.Register(f, "consulkv")
	bridge.Register(f, "consulkv-unix")
}

type Factory struct{}

func (f *Factory) New(uri *url.URL) bridge.RegistryAdapter {
	config := consulapi.DefaultConfig()
	path := uri.Path
	if uri.Scheme == "consulkv-unix" {
		spl := strings.SplitN(uri.Path, ":", 2)
		config.Address, path = "unix://"+spl[0], spl[1]
	} else if uri.Host != "" {
		config.Address = uri.Host
	}
	client, err := consulapi.NewClient(config)
	if err != nil {
		log.Fatal("consulkv: ", uri.Scheme)
	}
	return &ConsulKVAdapter{client: client, path: path}
}

type ConsulKVAdapter struct {
	client *consulapi.Client
	path   string
}

// Ping will try to connect to consul by attempting to retrieve the current leader.
func (r *ConsulKVAdapter) Ping() error {
	status := r.client.Status()
	leader, err := status.Leader()
	if err != nil {
		return err
	}
	log.Println("consulkv: current leader ", leader)

	return nil
}

func (r *ConsulKVAdapter) Register(service *bridge.Service) error {
	log.Println("Register")
	path := r.path[1:] + "/" + service.Name + "/" + service.ID
	port := strconv.Itoa(service.Port)
	addr := net.JoinHostPort(service.IP, port)
	log.Printf("path: %s", path)
	_, err := r.client.KV().Put(&consulapi.KVPair{Key: path, Value: []byte(addr)}, nil)
	if err != nil {
		log.Println("consulkv: failed to register service:", err)
	}
	return err
}

func (r *ConsulKVAdapter) Deregister(service *bridge.Service) error {
	path := r.path[1:] + "/" + service.Name + "/" + service.ID
	_, err := r.client.KV().Delete(path, nil)
	if err != nil {
		log.Println("consulkv: failed to deregister service:", err)
	}
	return err
}

func (r *ConsulKVAdapter) Refresh(service *bridge.Service) error {
	return nil
}

func (r *ConsulKVAdapter) Services() ([]*bridge.Service, error) {
	return []*bridge.Service{}, nil
}
