# Openwhisk Metric Support

Openwhick contains the capability to send metric information to a statsd server. This capability is disabled per default. Instead metric information is normally written to the log files in logmarker format.

## Configuration

Both capabilties can be enabled or disabled separately during deployment via Ansible configuration in the 'goup_vars/all' file of an  environment.

There are four configurations options available:

- **metrics_log** [true / false  (default: true)] 

  Enable/disable whether the metric information is written out to the log files in logmarker format. 
  
  *Beware: Even if set to false all messages adjourning the log markers are still written out to the log*

- **metrics_kamon** [true / false (default: false)]

  Enable/disable whther metric information is send the configured statsd server.

- **metrics_kamon_statsd_host** [hostname or ip address]

  Hostname or ip address of the statsd server

- **metrics_kamon_statsd_port** [port number (default:8125)]

  Port number of the statsd server


Example configuration:

```
metrics_kamon: true
metrics_kamon_statsd_host: '192.168.99.100'
metrics_kamon_statsd_port: '8125'
metrics_log: true
```

## Testing the statsd metric support

The Kamon project privides an integrated docker image containing statsd and a connected Grafana dashboard via [this Github project](https://github.com/kamon-io/docker-grafana-graphite). This image is helpful for testing the metrices sent via statsd.

Please follow these [instructions](https://github.com/kamon-io/docker-grafana-graphite/blob/master/README.md) to start the docker image in your local docker environment.

The docker image exposes statsd via the (standard) port 8125 and a Graphana dashboard via port 8080 on your docker host.

The address of your docker host has to be configured in the `metrics_kamon_statsd_host` configuration property.
