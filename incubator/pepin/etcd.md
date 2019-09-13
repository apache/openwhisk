
# Installing etcd in k8s

https://github.com/helm/charts/tree/master/incubator/etcd

```shell script
helm repo add incubator http://storage.googleapis.com/kubernetes-charts-incubator


#use etcd 3.3.10-1
helm install --name router-etcd --set image.tag=3.3.10-1,persistentVolume.enabled=true,persistentVolume.storage=256Mi  incubator/etcd

#verify
kubectl exec router-etcd-0 -- sh -c 'etcdctl cluster-health'

# restart if needed (after laptop sleep may be required)

kubectl scale sts router-etcd --replicas=0
kubectl scale sts router-etcd --replicas=3

#or delete and reinstall
helm delete --purge router-etcd
kubectl delete pvc datadir-router-etcd-0
kubectl delete pvc datadir-router-etcd-1
kubectl delete pvc datadir-router-etcd-2

```
# Running ow-router locally

To debug ow-router, you need to connect to etcd running in k8s. 

To expose the etcd statefulset, you can use a LoadBalancer that references one of the etcd pods:

```shell script
kubectl apply -f etcd-loadbalancer.yaml
ETCD_IP=$(kubectl get service etcd-0 -o jsonpath={.status.loadBalancer.ingress[0].ip})
echo $ETCD_IP
```
