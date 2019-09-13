package main

import (
	"context"
	"errors"
	"fmt"
	"git.corp.adobe.com/bladerunner/pepin/internal/pkg/datastore"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"time"

	"git.corp.adobe.com/bladerunner/pepin/internal/pkg"
	"git.corp.adobe.com/bladerunner/pepin/internal/pkg/pool"
	"git.corp.adobe.com/bladerunner/pepin/internal/pkg/pool/knative"
	"github.com/gin-contrib/pprof"
	"github.com/gin-gonic/gin"
	flag "github.com/spf13/pflag"
	"github.com/valyala/fasthttp"
	"go.uber.org/zap"
	"k8s.io/apimachinery/pkg/util/uuid"
)

const (
	statsServerAddr = ":8080"
)

var (
	masterURL           = flag.String("master", "", "The address of the Kubernetes API server. Overrides any value in kubeconfig. Only required if out-of-cluster.")
	kubeconfig          = flag.String("kubeconfig", "", "Path to a kubeconfig. Only required if out-of-cluster.")
	etcdURL             = flag.String("etcd", "", "The address of the etcd server.")
	gateway             = flag.String("gateway", "", "Specify the gloo gateway endpoint if running router outside of k8s cluster. e.g. `192.168.99.103:32453`")
	port                = flag.Int("port", 8080, "Port to listen on. Default is 8080")
	routerId            = string(uuid.NewUUID())
	production          = flag.BoolP("production", "p", false, "Set if you are running in production mode")
	serviceTemplatePath = flag.String("servicedef", "", "Path to a file that has the knative service definition to use.")
	defaultImageName    = flag.String("imagename", "gcr.io/knative-samples/autoscale-go:0.1", "Default image name to use for all knative services.")
	namespace           = flag.String("namespace", "ow-pepin", "Default namespace where knative services will be created.")
	db                  = flag.String("db", "mem", "The db impl to use. Default is \"mem\", and in-memory volatile db.")
)

var resourcePool pool.Pool
var logger *zap.SugaredLogger
var useGateway bool
var ds datastore.Datastore

func init() {
	flag.Parse()
	var err error
	logger, err = pkg.GetLogger(*production)
	if err != nil {
		panic("Can't get logging!")
	}
}

var downstreamTimeout time.Duration

func main() {
	var err error
	if err != nil {
		logger.Fatal("Could not create resource pool.", zap.Error(err))
	}
	downstreamTimeout = 30 * time.Second
	client := &fasthttp.Client{}
	defer logger.Sync()
	logger.Debugf("Starting router with "+
		"kubeconfig: %s, "+
		"master-url: %s, "+
		"etcd-url: %s, "+
		"gateway: %s, "+
		"port: %d, "+
		"db: %s", *kubeconfig, *masterURL, *etcdURL, *gateway, *port, *db)

	var storage = pool.LocalStorage
	if etcdURL != nil {
		logger.Infof("using etcd for storage at %s", *etcdURL)
		storage = pool.EtcdStorage
	}

	switch *db {
	case "mem":
		ds = &datastore.MemoryDatastore{}
	default:
		logger.Panicf("unknown database type %s", *db)
	}
	err = ds.InitStore()
	if err != nil {
		logger.Fatalw("Error initing Datastore", zap.Error(err))
	}

	resourcePool, err = knative.NewResourcePool(knative.PoolConfig{
		PoolStorageConfig:  pool.PoolStorageConfig{Kind: storage},
		MasterURL:          *masterURL,
		KubeConfig:         *kubeconfig,
		EtcdURL:            *etcdURL,
		StemcellCount:      2,
		ServiceTemplate:    *serviceTemplatePath,
		K8sActionNamespace: *namespace,
		DefaultImageName:   *defaultImageName,
	}, routerId)

	if err != nil {
		logger.Fatalw("Error building KNative Resource Pool", zap.Error(err))
	}
	//create localClient for external-to-cluster routing
	localClient := &fasthttp.HostClient{
		Addr: *gateway, // e.g. "192.168.99.103:32453",
	}
	if *gateway != "" {
		useGateway = true
		logger.Infof("Using gateway at %s to route knative requests", localClient.Addr)
	}

	//http://localhost:8001/apis/serving.knative.dev/v1alpha1/services

	if *production {
		gin.SetMode(gin.ReleaseMode)
	}
	router := gin.Default()
	runtime.SetBlockProfileRate(1)
	if !*production {
		pprof.Register(router)
	}

	router.POST("/namespaces/:namespace/actions/:action", func(context *gin.Context) {
		//get action from path
		action := context.Params.ByName("action")
		namespace := context.Params.ByName("namespace")
		a, err := ds.GetAction(namespace, action)
		if err != nil {
			context.AbortWithError(404, errors.New(fmt.Sprintf("action %s/%s not found", namespace, action)))
		} else {
			allocatedChan, errChan := resourcePool.Allocate(a, routerId)
			select {
			case action := <-allocatedChan:
				req := fasthttp.AcquireRequest()
				if useGateway {
					req.Header.SetHost(action.ExternalUrl.Host)
					logger.Debugf("using external knative request %s", action.ExternalUrl.Host)
				} else {
					logger.Debugf("Requesting Response from service: %s", action.Url.String())
					req.SetRequestURI(action.Url.String())
				}
				//copy query params onto URI
				//TODO: also pass headers and body
				if len(context.Request.URL.Query()) > 0 {
					req.SetRequestURI(req.URI().String() + "?" + context.Request.URL.RawQuery)

				}
				logger.Debugf("Requesting Response from service: %s", req.String())
				resp := fasthttp.AcquireResponse()

				var err error
				if useGateway {
					err = localClient.Do(req, resp)
				} else {
					err = client.Do(req, resp)
				}
				if err != nil {
					context.String(http.StatusInternalServerError, err.Error())
				}
				logger.Debugf("Action responded.")
				sc := resp.StatusCode()
				bodyBytes := resp.Body()
				context.String(sc, string(bodyBytes))
				return
			case e := <-errChan:
				logger.Debugf("Error reported from allocation channel.")
				context.String(http.StatusInternalServerError, e.Error())
				return
			case <-time.After(downstreamTimeout):
				logger.Debugf("Timed out waiting for service response.")
				context.String(http.StatusInternalServerError, "Requesting resources timed out.")
				return
			}
		}
	})
	router.GET("/healthz", func(context *gin.Context) {
		context.JSON(200, gin.H{
			"message": "pong",
		})
	})

	srv := &http.Server{
		Addr:    fmt.Sprintf(":%d", *port),
		Handler: router,
	}
	go func() {
		// service connections
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("listen: %s\n", err)
		}
	}()

	//channel for shutdown signal
	quit := make(chan os.Signal)

	//periodic cleanup
	gcTicker := time.NewTicker(5 * time.Second)
	go func() {
		for {
			select {
			case <-gcTicker.C:
				resourcePool.GC(routerId)
			}
		}
	}()

	// Wait for interrupt signal to gracefully shutdown the server with
	// a timeout of 5 seconds.

	signal.Notify(quit, os.Interrupt)
	<-quit
	logger.Info("Shutdown Server ...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("Server Shutdown:", err)
	}
	gcTicker.Stop()
	resourcePool.Close()
	logger.Info("Server exiting")
}
