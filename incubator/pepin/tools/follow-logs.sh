#!/bin/bash
kubectl logs --tail=100 -f $(kubectl get pods | grep $1 | cut -d " " -f 1)
