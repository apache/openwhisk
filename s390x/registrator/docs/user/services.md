# Service Object

Registrator is primarily concerned with services that would be added to a
service discovery registry. In our case, a service is anything listening on a
port. If a container listens on multiple ports, it has multiple services.

Services are created with information from the container, including user-defined
metadata on the container, into an intermediary service object. This service
object is then passed to a registry backend to try and place as much of this
object into a particular registry.

	type Service struct {
		ID    string               // unique service instance ID
		Name  string               // service name
		IP    string               // IP address service is located at
		Port  int                  // port service is listening on
		Tags  []string             // extra tags to classify service
		Attrs map[string]string    // extra attribute metadata
	}

## Container Overrides

The fields `Name`, `Tags`, `Attrs`, and `ID` can be overridden by user-defined
container metadata. You can use environment variables or labels prefixed with
`SERVICE_` or `SERVICE_x_` to set values, where `x` is the internal exposed port.
For example `SERVICE_NAME=customerdb` and `SERVICE_80_NAME=api`.

You use a port in the key name to refer to a particular service on that port.
Metadata variables without a port in the name are used as the default for all
services or can be used to conveniently refer to the single exposed service.

The `Attrs` field is populated by metadata using any other field names in the
key name. For example, `SERVICE_REGION=us-east`.

Since metadata is stored as environment variables or labels, the container
author can include their own metadata defined in the Dockerfile. The operator
will still be able to override these author-defined defaults.


## Detecting Services

By default, you can expect Registrator to pick up services from containers that
have *explicitly published ports* (eg, using `-p` or `-P`). This is true for
containers running in host network mode as well, so you'll have to publish ports
even though it doesn't do anything networking wise:

	$ docker run --net=host -p 8080:8080 -p 8443:8443 ...

If running with the `-internal` option, it will instead look for exposed ports.
These can be implicitly set from the Dockerfile or explicitly set with `docker run
--expose=8080 ...`.

You can also tell Registrator to ignore a container by setting a
label or environment variable for `SERVICE_IGNORE`.

If you need to ignore individual service on some container, you can use 
`SERVICE_<port>_IGNORE=true`.

## Service Name

Service names are what you use in service discovery lookups. By default, the
service name is determined by this pattern:

	<base(container-image)>[-<exposed-port> if >1 ports]

Using the base of the container image, if the image is `gliderlabs/foobar`, the
service name is `foobar`. If the image is `redis` the service name is simply
`redis`.

Additionally, if a container has multiple exposed ports, it will append the
internal exposed port to differentiate from each other. For example, an image
`nginx` with two exposed ports, 80 and 443, will produce two services named
`nginx-80` and `nginx-443`.

You can override this default name with label or environment variable
`SERVICE_NAME` or `SERVICE_x_NAME`, where `x` is the internal exposed port. Note
that if a container has multiple exposed ports then setting `SERVICE_NAME` will
still result in multiple services named `SERVICE_NAME-<exposed port>`.

## IP and Port

IP and port make up the address that the service name resolves to. There are a
number of ways Registrator can determine IP and port depending your setup. By
default, port is the public *published* port and the IP is going to try and be
your host IP.

Since determining the right IP is difficult to do automatically, it's recommended
to use the `-ip` option to explicitly tell Registrator what IP to use.

If you use the `-internal` option, Registrator will use the *exposed* port **and
Docker-assigned internal IP of the container**.

## Tags and Attributes

Tags and attributes are extra metadata fields for services. Not all backends
support them. In fact, currently Consul supports tags and none support
attributes.

Attributes can also be used by backends for registry specific features, not just
generic metadata. For example, Consul uses them for specifying HTTP health
checks.

## Unique ID

The ID is a cluster-wide unique identifier for this service instance. For the
most part, it's an implementation detail, as users typically use service names,
not their IDs. Registrator comes up with a human-friendly string that encodes
useful information in the ID based on this pattern:

	<hostname>:<container-name>:<exposed-port>[:udp if udp]

The ID includes the hostname to help you identify which host this service is
running on. This is why running Registrator in host network mode or setting
Registrator's hostname to the host's hostname is important. Otherwise it will be
the ID of the Registrator container, which is not terribly useful.

The name of the container for this service is also included. It uses the name
instead of container ID because it's more human-friendly and user configurable.

To identify this particular service in the container, it uses the internal
exposed port. This represents the port the service is listening on inside the
container. We use this because it likely better represents the service than the
publicly published port. A published port might be an arbitrary 54292, whereas
the exposed port might be 80, showing that it's an HTTP service.

Lastly, if the service is identified as UDP, this is included in the ID to
differentiate from a TCP service that could be listening on the same port.

Although this can be overridden on containers with `SERVICE_ID` or
`SERVICE_x_ID`, it is not recommended.

## Examples

### Single service with defaults

	$ docker run -d --name redis.0 -p 10000:6379 progrium/redis

Results in `Service`:

	{
		"ID": "hostname:redis.0:6379",
		"Name": "redis",
		"Port": 10000,
		"IP": "192.168.1.102",
		"Tags": [],
		"Attrs": {}
	}

### Single service with metadata

	$ docker run -d --name redis.0 -p 10000:6379 \
		-e "SERVICE_NAME=db" \
		-e "SERVICE_TAGS=master,backups" \
		-e "SERVICE_REGION=us2" progrium/redis

Results in `Service`:

	{
		"ID": "hostname:redis.0:6379",
		"Name": "db",
		"Port": 10000,
		"IP": "192.168.1.102",
		"Tags": ["master", "backups"],
		"Attrs": {"region": "us2"}
	}

Keep in mind not all of the `Service` object may be used by the registry backend. For example, currently none of them support registering arbitrary attributes. This field is there for future use.

### Multiple services with defaults

	$ docker run -d --name nginx.0 -p 4443:443 -p 8000:80 progrium/nginx

Results in two `Service` objects:

	[
		{
			"ID": "hostname:nginx.0:443",
			"Name": "nginx-443",
			"Port": 4443,
			"IP": "192.168.1.102",
			"Tags": [],
			"Attrs": {},
		},
		{
			"ID": "hostname:nginx.0:80",
			"Name": "nginx-80",
			"Port": 8000,
			"IP": "192.168.1.102",
			"Tags": [],
			"Attrs": {}
		}
	]

### Multiple services with metadata

	$ docker run -d --name nginx.0 -p 4443:443 -p 8000:80 \
		-e "SERVICE_443_NAME=https" \
		-e "SERVICE_443_ID=https.12345" \
		-e "SERVICE_443_SNI=enabled" \
		-e "SERVICE_80_NAME=http" \
		-e "SERVICE_TAGS=www" progrium/nginx

Results in two `Service` objects:

	[
		{
			"ID": "https.12345",
			"Name": "https",
			"Port": 4443,
			"IP": "192.168.1.102",
			"Tags": ["www"],
			"Attrs": {"sni": "enabled"},
		},
		{
			"ID": "hostname:nginx.0:80",
			"Name": "http",
			"Port": 8000,
			"IP": "192.168.1.102",
			"Tags": ["www"],
			"Attrs": {}
		}
	]

### Using labels to define metadata

	$ docker run -d --name redis.0 -p 10000:6379 \
		-l "SERVICE_NAME=db" \
		-l "SERVICE_TAGS=master,backups" \
		-l "SERVICE_REGION=us2" dockerfile/redis

Results in `Service`:

	{
		"ID": "hostname:redis.0:6379",
		"Name": "db",
		"Port": 10000,
		"IP": "192.168.1.102",
		"Tags": ["master", "backups"],
		"Attrs": {"region": "us2"}
	}
