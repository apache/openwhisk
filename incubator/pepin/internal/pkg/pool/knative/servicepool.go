package knative

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"git.corp.adobe.com/bladerunner/pepin/internal/pkg"
	"io/ioutil"
	"net/url"
	"sync"
	"text/template"
	"time"

	"git.corp.adobe.com/bladerunner/pepin/internal/pkg/datastore"
	"git.corp.adobe.com/bladerunner/pepin/internal/pkg/pool"
	"github.com/coreos/etcd/clientv3"
	"github.com/coreos/etcd/clientv3/clientv3util"
	servingMeta "github.com/knative/serving/pkg/apis/serving/v1alpha1"
	servingClient "github.com/knative/serving/pkg/client/clientset/versioned"
	"go.uber.org/zap"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/uuid"
	"k8s.io/apimachinery/pkg/watch"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
)

var logger *zap.SugaredLogger
var services *localServicePool
var defaultServiceTemplate = `
{
    "apiVersion": "serving.knative.dev/v1alpha1",
    "kind": "Service",
    "metadata": {
        "name": "{{.Name}}",
        "namespace": "{{.Namespace}}",
		"labels": {
			"ow-kind": "nodejs-10",
			"stemcell": "true",
			"ow-namespace": "{{.OwNamespace}}",
			"ow-action": "{{.OwAction}}"
		}
    },
    "spec": {
        "template": {
			"metadata" : {
				"annotations": {
					"autoscaling.knative.dev/class": "hpa.autoscaling.knative.dev",
					"autoscaling.knative.dev/metric": "cpu",
					"autoscaling.knative.dev/target": "50",
					"autoscaling.knative.dev/minScale": "1",
					"autoscaling.knative.dev/maxScale": "100"
				}
			},
            "spec": {
                "containers": [
                    {
                        "env": [
                            {
                                "name": "TARGET",
                                "value": "Go Sample v1 - {{.Name}}"
                            }
                        ],
                        "image": "{{.ImageName}}",
                        "name": "user-container",
                        "resources": {}
                    }
                ]
            }
        }
    }
}
`

//TODO: once service is allocated and receiving traffic, we could reset the autoscaling.knative.dev/minScale to 0, and purge services who reach the 0 scale? Or leave the action mapped to this service indefinitely (but pods will scale to 0)?
//ExecutionProviders
var kinds map[string]ExecutionProvider

type ExecutionProvider int

const (
	Knative ExecutionProvider = iota
	OpenWhisk
)

func init() {
	logger = zap.NewExample().Sugar()
	//TODO: load kind/provider mapping from ConfigMap?
	kinds = make(map[string]ExecutionProvider)
	kinds["nodejs-10"] = Knative
	kinds["nodejs-ow"] = OpenWhisk
}

type localServicePool struct {
	sync.RWMutex
	data                  map[string]pool.Action
	stemcells             []pool.Resource
	config                PoolConfig
	etcd                  *clientv3.Client
	clientSet             *servingClient.Clientset
	routerId              string
	pendingRouterMappings *RouterMappings
	serviceTemplate       *template.Template
}

func (s localServicePool) Close() {
	logger.Infof("closing etcd client...")
	s.etcd.Close()
}

type RouterMappings struct { //a Set of strings to track pending router -> service usage
	sync.RWMutex
	m map[string]struct{}
}

type PoolConfig struct {
	pool.PoolStorageConfig
	MasterURL          string
	KubeConfig         string
	EtcdURL            string
	StemcellCount      int
	ServiceTemplate    string
	DefaultImageName   string
	K8sActionNamespace string
}

type templateData struct {
	Name        string
	Namespace   string
	ImageName   string
	OwNamespace string
	OwAction    string
}

type actionMapping struct {
	url  url.URL `json:"url"`
	name string  `json:"name"`
}

var (
	etcdDialTimeout      = 2 * time.Second
	etcdRequestTimeout   = 10 * time.Second
	serviceIdleTimeout   = 30 * time.Second
	routerMappingRefresh = make(chan string)
)

func (s *localServicePool) buildKNativeService(metaData datastore.ActionMetaData) (pool.Resource, error) {
	var err error
	uuid := string(uuid.NewUUID())
	serviceObj := servingMeta.Service{}

	if err != nil {
		logger.Errorf("Could not parse knative service template", err)
		return pool.Resource{}, err
	}
	var templateBuffer bytes.Buffer
	templatedata := templateData{
		Name:        fmt.Sprintf("ow-%s", uuid),
		Namespace:   s.config.K8sActionNamespace,
		ImageName:   s.config.DefaultImageName,
		OwNamespace: metaData.Namespace,
		OwAction:    metaData.Name,
	}
	err = s.serviceTemplate.Execute(&templateBuffer, templatedata)
	if err != nil {
		logger.Errorf("Could not parse knative service template", err)
		return pool.Resource{}, err
	}

	json.Unmarshal([]byte(templateBuffer.String()), &serviceObj)
	logger.Debugf("Installing service with JSON: %s", templateBuffer.String())
	_, err = s.clientSet.ServingV1alpha1().Services(s.config.K8sActionNamespace).Create(&serviceObj)
	if err != nil {
		logger.Errorf("Could not create service in knative.", err)
		return pool.Resource{}, err
	}

	servicesWatcher, err := s.clientSet.ServingV1alpha1().Services(s.config.K8sActionNamespace).Watch(v1.ListOptions{
		FieldSelector: "metadata.name=" + templatedata.Name,
	})

	if err != nil {
		return pool.Resource{}, err
	}
	servicesResultsChan := servicesWatcher.ResultChan()
	for event := range servicesResultsChan {
		service, ok := event.Object.(*servingMeta.Service)
		if !ok {
			logger.Error("Could not get service object from event.")
		}
		switch event.Type {
		case watch.Modified:
			logger.Debugf("Modification of service: %s", service.Name)
			if service.Status.Conditions != nil {
				var allReady = true
				for _, cond := range service.Status.Conditions {
					if cond.Status != corev1.ConditionTrue {
						logger.Debugf("Condition Still not true: %s", cond.Type)
						allReady = false
					}
				}
				if allReady && len(service.Status.Conditions) > 0 {
					servicesWatcher.Stop()
					latestRev := service.Status.LatestReadyRevisionName
					internalUrl := url.URL{Scheme: "http", Host: latestRev}
					logger.Debugf("New service available at internal url %s and external url %s", internalUrl.String(), service.Status.RouteStatusFields.URL.String())
					//TODO: don't use "default", but the appropriate namespace
					//latestRev := fmt.Sprintf("%s", service.Status.LatestReadyRevisionName)
					return pool.Resource{Provider: "knative", Name: templatedata.Name, Kind: "nodejs-10", Url: internalUrl, ExternalUrl: url.URL(*service.Status.RouteStatusFields.URL)}, nil
				}
			}
		}
	}
	return pool.Resource{}, fmt.Errorf("Could not get service URL in time.")
}

func (s *localServicePool) tearDownKNativeService(svc pool.Resource) error {
	var err error

	logger.Debugf("Deleting service with name: %s", svc.Name)
	grace := int64(0)
	p := metav1.DeletePropagationForeground
	options :=
		metav1.DeleteOptions{
			GracePeriodSeconds: &grace,
			PropagationPolicy:  &p,
		}

	err = s.clientSet.ServingV1alpha1().Services(s.config.K8sActionNamespace).Delete(svc.Name, &options)
	if err != nil {
		logger.Errorf("Could not create service in knative.", err)
	}
	return err

	//TODO: can we watch for proper teardown? This API for service delete is not responding as quickly as kn cli
	//servicesWatcher, err := s.clientSet.ServingV1alpha1().Services(namespace).Watch(v1.ListOptions{
	//	FieldSelector: "metadata.name=" + svc.Name,
	//})

}

func (s *localServicePool) Allocate(metadata datastore.ActionMetaData, routerId string) (<-chan pool.Action, <-chan error) {
	logger, _ = pkg.GetLogger(true)
	reply := make(chan pool.Action)
	errChan := make(chan error)
	go func(m datastore.ActionMetaData) {
		datakey := m.Namespace + "/" + m.Name
		s.RLock()
		if a, ok := s.data[datakey]; ok {
			s.RUnlock()
			logger.Debugf("Sending request to allocated service: %s with url %s", a.Resource.Name, a.Url.String())
			reply <- a
			//send refresh notice to router->service mapping
			logger.Debugf("total mappings to refresh is now %d", len(s.pendingRouterMappings.m))
			routerMappingRefresh <- a.Resource.Name
		} else {
			s.RUnlock()
			//local lock for duration of allocation
			s.Lock()
			var newAction *pool.Action
			//double check (may be populated during concurrent execution in separate lock)
			if a, ok := s.data[datakey]; ok {
				logger.Debugf("found mapped action after lock")
				newAction = &a
			} else {
				if services.config.Kind == pool.EtcdStorage {
					//get service from etcd (may have been allocated by other router instance)
					ctx, _ := context.WithTimeout(context.Background(), etcdRequestTimeout)
					mappingKey := fmt.Sprintf("/openwhisk/router/knative/actions/%s/%s", metadata.Namespace, metadata.Name)
					logger.Infof("checking for key %s", mappingKey)
					resp, err := s.etcd.Get(ctx, mappingKey)
					if err != nil {
						logger.Error("Could not access etcd", err)
						errChan <- err
					}
					if len(resp.Kvs) > 0 {
						serviceId := string(resp.Kvs[0].Value)
						//found an existing service in use by some other router; use it
						//TODO: is there a better way to rehydrate pool.Resource from service id?
						actionUrl, _ := url.Parse(fmt.Sprintf("http://%s.default.svc.cluster.local", serviceId))
						externalActionUrl, _ := url.Parse(fmt.Sprintf("http://%s.default.example.com", serviceId))

						newAction = &pool.Action{
							Resource:       pool.Resource{Provider: "knative", Name: serviceId, Url: *actionUrl, ExternalUrl: *externalActionUrl},
							ActionMetaData: m,
						}

						addRouter(s.etcd, serviceId, routerId)

						logger.Debugf("Sending request to allocated service found in etcd: %s", a.Url.String())
					} else {
						//no mapping exists, create a new one, from an unused service
						logger.Infof("attempting to allocate stemcell for %s", mappingKey)
						stemcell, err := allocateStemcell(m)

						if err != nil {
							logger.Error("Could not allocate stemcell", err)
							errChan <- err
						} else {
							logger.Infof("allocated stemcell at %s", stemcell.Url)
							newAction = &pool.Action{
								Resource:       stemcell,
								ActionMetaData: m,
							}
							//TODO: is there a generic channel appropriate for tracking errors during stemcell creation occuring in separate thread?
							go fillStemcells()
						}
					}
				}

				if newAction == nil {
					logger.Debugf("No available service or stemcell, available for: %s/%s.", metadata.Namespace, metadata.Name)

					//determine the ExecutionProvider
					var resource pool.Resource
					var err error
					if ep, ok := kinds[m.Kind]; ok {
						switch ep {
						case Knative:
							resource, err = s.buildKNativeService(m)
						case OpenWhisk:
							err = errors.New("OpenWhisk not implemented...")
						}
					} else {
						err = errors.New(fmt.Sprintf("no execution provider for kind %s", m.Kind))
					}

					if err != nil {
						logger.Error("Could not create knative service", err)
						errChan <- err
					} else {
						newAction = &pool.Action{
							Resource:       resource,
							ActionMetaData: m,
						}
					}
				}
				s.data[datakey] = *newAction
			}
			reply <- *newAction
			s.Unlock()
		}
		close(reply)
		close(errChan)
	}(metadata)
	return reply, errChan

}

func NewResourcePool(storageConfig PoolConfig, routerId string) (p pool.Pool, e error) {
	var err error
	logger, err = pkg.GetLogger(true)
	if err != nil {
		panic("Can't get logging!")
	}
	//if storageConfig.MasterURL == "" && storageConfig.KubeConfig == "" {
	//	return nil, errors.New("must set master or kubeconfig paths")
	//}
	services = &localServicePool{
		data:                  make(map[string]pool.Action),
		config:                storageConfig,
		routerId:              routerId,
		pendingRouterMappings: &RouterMappings{m: make(map[string]struct{})},
	}

	if storageConfig.ServiceTemplate == "" {
		tmpl, err := template.New("service-template").Parse(defaultServiceTemplate)
		if err != nil {
			logger.Fatalf("Could not parse default service template.", err)
		}
		services.serviceTemplate = tmpl
	} else {
		templateData, err := ioutil.ReadFile(storageConfig.ServiceTemplate)
		if err != nil {
			logger.Fatalf("Could not read proposed service template.", err)
		}
		tmpl, err := template.New("service-template").Parse(string(templateData))
		if err != nil {
			logger.Fatalf("Could not parsed proposed service template.", err)
		}
		services.serviceTemplate = tmpl
	}

	var clientConfig *rest.Config
	if storageConfig.MasterURL == "" && storageConfig.KubeConfig == "" {
		clientConfig, err = rest.InClusterConfig()
		if err != nil {
			logger.Fatalf("Could not get KNative Clientset", err)
		}
	} else {
		clientConfig, err = clientcmd.BuildConfigFromFlags(services.config.MasterURL, services.config.KubeConfig)
		if err != nil {
			logger.Fatalf("Could not get KNative Clientset", err)
		}
	}

	services.clientSet, err = servingClient.NewForConfig(clientConfig)
	if err != nil {
		logger.Fatalf("Could not get KNative Clientset", err)
	}

	registeredServices, err := services.clientSet.ServingV1alpha1().Services(services.config.K8sActionNamespace).List(v1.ListOptions{
		LabelSelector: "ow-namespace, ow-action",
	})

	if err != nil {
		logger.Warnf("Could not read existing services on start: %s", err.Error())
	}

	if storageConfig.Kind == pool.EtcdStorage {
		cfg := clientv3.Config{
			Endpoints: []string{storageConfig.EtcdURL},

			// set timeout per request to fail fast when the target endpoint is unavailable
			DialTimeout: etcdDialTimeout,
		}
		etcd, err := clientv3.New(cfg)
		if err != nil {
			logger.Fatalf("Could not connect to etcd. %s", err)
		}
		//TODO: where should this be cleaned up?
		//defer etcd.Close()
		services.etcd = etcd
	}

	services.Lock()
	if len(registeredServices.Items) > 0 {
		logger.Debugf("Warming up cache from k8s.")
	} else {
		logger.Debugf("Found no existing KNative services to cache.")
	}
	for _, aService := range registeredServices.Items {
		//var ns = aService.GetObjectMeta().GetLabels()["ow-namespace"]
		//var name = aService.GetObjectMeta().GetLabels()["ow-action"]
		var kind = aService.GetObjectMeta().GetLabels()["ow-kind"]
		//logger.Debugf("Registering route from action %s/%s -> %s", ns, name, aService.Status.Address.URL.String())

		//if stemcell {

		if storageConfig.Kind == pool.EtcdStorage {
			//exclude services that are already mapped in etcd
			ctx, _ := context.WithTimeout(context.Background(), etcdRequestTimeout)
			serviceKey := fmt.Sprintf("/openwhisk/router/knative/services/%s", aService.Name)

			resp, err := services.etcd.Get(ctx, serviceKey)
			if err != nil {
				logger.Error("Could not access etcd", err)
			}
			//if no service ref in etcd, treat it as a stem cell
			if len(resp.Kvs) == 0 {
				services.stemcells = append(services.stemcells, pool.Resource{Provider: "knative", Name: aService.Name, Kind: kind, Url: url.URL(*aService.Status.Address.URL), ExternalUrl: url.URL(*aService.Status.RouteStatusFields.URL)})
				logger.Debugf("new stemcell size %d", len(services.stemcells))
			} else {
				logger.Error("Found a stemcell in use %s", serviceKey)
			}
		}

		//don't track namespace/action in service metadata anymore (it is tracked in etcd)
		//} else {
		//	services.data[ns+"/"+name] = pool.Action{
		//		Resource: pool.Resource{
		//			Provider: "knative",
		//			Name:     aService.Name,
		//			Url:      url.URL(*aService.Status.Address.URL),
		//		},
		//		ActionMetaData: pool.ActionMetaData{
		//			Name:      name,
		//			Namespace: ns,
		//			Kind:      kind,
		//		},
		//	}
		//}
	}

	if len(services.stemcells) > storageConfig.StemcellCount {
		logger.Warnf("More stemcells than this configuration can use: found %d, configured for %d", services.stemcells, storageConfig.StemcellCount)
	}

	fillStemcells()
	go handleMappingRefresh()
	services.Unlock()

	//etcd setup (if needed)
	return services, nil
}

func handleMappingRefresh() {
	//for each action, refresh etcd
	for {
		svc := <-routerMappingRefresh
		services.pendingRouterMappings.RLock()
		_, present := services.pendingRouterMappings.m[svc]
		services.pendingRouterMappings.RUnlock()

		if !present {
			services.pendingRouterMappings.Lock()
			services.pendingRouterMappings.m[svc] = struct{}{}
			services.pendingRouterMappings.Unlock()
			logger.Debugf("adding mapping refresh for %s, total mappings to refresh is now %d", svc, len(services.pendingRouterMappings.m))
		}
	}
}

func (s *localServicePool) GC(routerId string) {
	logger, _ = pkg.GetLogger(true)
	logger.Infof("Cleaning up unused services; %d stemcells currently available", len(services.stemcells))
	if s.config.Kind == pool.EtcdStorage {

		if len(s.pendingRouterMappings.m) == 0 {
			logger.Debugf("No refreshes pending for router id %s", routerId)
		} else {
			//for each service this router is tracking, refresh etcd
			s.pendingRouterMappings.Lock()
			for svc := range s.pendingRouterMappings.m {
				logger.Debugf("Refreshing router mapping for service %s and router %s", svc, routerId)
				refreshMapping(s.etcd, svc, routerId)
			}
			//reset the pending mappings to empty
			s.pendingRouterMappings.m = make(map[string]struct{})
			s.pendingRouterMappings.Unlock()
		}

		//remove action->service references for any actions with no router
		ctx, _ := context.WithTimeout(context.Background(), etcdRequestTimeout)
		//TODO: this may return a lot of keys?
		resp, err := s.etcd.Get(ctx, "/openwhisk/router/knative/actions", clientv3.WithPrefix())
		if err != nil {
			logger.Error("Could not access etcd for GC", err)
			return
		}
		if len(resp.Kvs) > 0 {
			for _, kv := range resp.Kvs {
				svc := string(kv.Value)
				//if no key for this router, delete the action mapping and the service
				routerKey := fmt.Sprintf("/openwhisk/router/knative/routers/%s/%s", routerId, svc)
				resp, err := s.etcd.Get(ctx, routerKey)
				if err != nil {
					logger.Error("Could not access etcd for GC", err)
				}
				if len(resp.Kvs) == 0 {
					//no router using this action, delete it

					//1. remove local ref
					var action *pool.Action

					for _, a := range s.data {
						if a.Resource.Name == svc {
							action = &a
							break
						}
					}
					if action == nil {
						logger.Errorf("Missing key in local data for service %s, size of data is %d", svc, len(s.data))
					} else {
						actionId := fmt.Sprintf("%s/%s", action.ActionMetaData.Namespace, action.ActionMetaData.Name)

						logger.Debugf("Deleting key in local data %s", actionId)
						s.Lock()
						delete(s.data, actionId)

						actionKey := fmt.Sprintf("/openwhisk/router/knative/actions/%s", actionId)

						//2. remove etcd ref
						resp, err := s.etcd.Delete(ctx, actionKey)
						if err != nil {
							logger.Errorf("Could not access etcd for GC", err)
						} else if resp.Deleted > 0 {
							logger.Infof("Deleted action %s during GC for router %s", actionKey, routerId)
						}

						//3. remove knative service
						s.tearDownKNativeService(action.Resource)
						s.Unlock()
					}
				} else {
					logger.Debugf("Service %s still in use by router %s", svc, routerId)
				}
			}
		}
	}
}

func fillStemcells() {
	logger.Debugf("replenishing stemcells: %d exist, require %d", len(services.stemcells), services.config.StemcellCount)
	for i := len(services.stemcells); i < services.config.StemcellCount; i++ {
		m := datastore.ActionMetaData{
			Kind: "nodejs-10",
		}
		resource, err := services.buildKNativeService(m)
		logger.Infof("Created new stemcell service %s at %s.", resource.Name, resource.Url)
		if err != nil {
			logger.Error("Could not create knative stemcell service", err)
		}
		services.stemcells = append(services.stemcells, resource)
	}
}
func allocateStemcell(metadata datastore.ActionMetaData) (pool.Resource, error) {
	//fail early if no stemcells
	if len(services.stemcells) == 0 {
		return pool.Resource{}, errors.New("No stem cells available")
	}

	logger.Debugf("allocating one of the %d stemcells", len(services.stemcells))
	actionId := fmt.Sprintf("%s/%s", metadata.Namespace, metadata.Name)
	for i, r := range services.stemcells {
		//keep looking till we find a matching kind AND can create the mapping (if etcd enabled)
		logger.Debugf("checking for kind %s; found kind %s", r.Kind, metadata.Kind)
		if r.Kind == metadata.Kind {
			//remove it from the available stemcells
			services.stemcells = append((services.stemcells)[:i], (services.stemcells)[i+1:]...)
			logger.Debugf("new stemcell size %d", len(services.stemcells))
			if services.config.Kind != pool.EtcdStorage || createMapping(services.etcd, r.Name, services.routerId, actionId) {
				return r, nil
			}
			//we were not able to allocate, because another router has allocated this stemcell
			//TODO: consider sharding stemcells to routers, to avoid allocation collisions or extra tracking?
			logger.Infof("stem cell service %s was already allocated, will try another ", r.Name)
		}
	}
	return pool.Resource{}, errors.New("Unable to allocate stem cells")
}
func createMapping(etcd *clientv3.Client, serviceId string, routerId string, actionId string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), etcdRequestTimeout)
	//could instead use m1 := concurrency.NewMutex(s1, lockpath)?

	actionKey := fmt.Sprintf("/openwhisk/router/knative/actions/%s", actionId)
	routerKey := fmt.Sprintf("/openwhisk/router/knative/routers/%s/%s", routerId, serviceId) //this key should have TTL to go away when router stops
	lease, _ := etcd.Grant(ctx, int64(serviceIdleTimeout.Seconds()))

	r, err := etcd.Txn(ctx).
		If(clientv3util.KeyMissing(routerKey),
			clientv3util.KeyMissing(actionKey)).
		Then(clientv3.OpPut(routerKey, serviceId, clientv3.WithLease(lease.ID)),
			clientv3.OpPut(actionKey, serviceId)).
		Commit()
	cancel()
	if err != nil {
		logger.Fatal(err)
	}
	logger.Debugf("mapping creation succeeded: %q", r.Succeeded)
	logger.Debugf("action key %s set to %s", actionKey, serviceId)
	logger.Debugf("routerKey key %s set to %s", routerKey, serviceId)
	return r.Succeeded
}
func addRouter(etcd *clientv3.Client, serviceId string, routerId string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), etcdRequestTimeout)
	//could instead use m1 := concurrency.NewMutex(s1, lockpath)?

	routerKey := fmt.Sprintf("/openwhisk/router/knative/routers/%s/%s", routerId, serviceId) //this key should have TTL to go away when router stops
	lease, _ := etcd.Grant(ctx, int64(serviceIdleTimeout.Seconds()))

	r, err := etcd.Txn(ctx).
		If(clientv3util.KeyMissing(routerKey)).
		Then(clientv3.OpPut(routerKey, serviceId, clientv3.WithLease(lease.ID))).
		Commit()
	cancel()
	if err != nil {
		logger.Fatal(err)
	}
	logger.Debugf("mapping creation succeeded: %q", r.Succeeded)
	logger.Debugf("routerKey key %s set to %s", routerKey, serviceId)
	return r.Succeeded
}
func refreshMapping(etcd *clientv3.Client, serviceId string, routerId string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), etcdRequestTimeout)
	routerKey := fmt.Sprintf("/openwhisk/router/knative/routers/%s/%s", routerId, serviceId) //this key should have TTL to go away when router stops
	lease, _ := etcd.Grant(ctx, int64(serviceIdleTimeout.Seconds()))

	r, err := etcd.Txn(ctx).
		If(clientv3util.KeyExists(routerKey)).
		Then(clientv3.OpPut(routerKey, serviceId, clientv3.WithLease(lease.ID))).
		Commit()
	cancel()
	if err != nil {
		logger.Infof("Key no longer exists, will not refresh %s", routerKey)
	}
	return r.Succeeded
}
