# :electric_plug: Apache OpenWhisk - Performance Tests
A few simple but efficient test suites for determining the maximum throughput and end-user latency of the Apache OpenWhisk system.

## Workflow
- A standard OpenWhisk system is deployed. (_Note that the edge NGINX router and API Gateway are currently left out. As a consequence, the tests talk directly to the controller._)
- All limits are set to 999999, which in our current use case means "No throttling at all".
- The deployment is using the docker setup proposed by the OpenWhisk development team: `overlay` driver and HTTP API enabled via a UNIX port.

The load is driven by the blazingly fast [`wrk`](https://github.com/wg/wrk).

#### Travis Machine Setup
The [machine provided by Travis](https://docs.travis-ci.com/user/ci-environment/#Virtualization-environments) has ~2 CPU cores (likely shared through virtualization) and 7.5GB memory.

## Suites

### Latency Test
Determines the end-to-end latency a user experience when doing a blocking invocation. The action used is a no-op so the numbers returned are the plain overhead of the OpenWhisk system.

- 1 HTTP request at a time (concurrency: 1)
- You can specify how long this test will run. Default are 30s.
- no-op action

**Note:** The throughput number has a 100% correlation with the latency in this case. This test does not serve to determine the maximum throughput of the system.

### Throughput Test
Determines the maximum throughput a user can get out of the system while using a single action. The action used is a no-op, so the numbers are plain OpenWhisk overhead. Note that the throughput does not directly correlate to end-to-end latency here, as the system does more processing in the background as it shows to the user in a blocking invocation.

- 4 HTTP requests at a time (concurrency: 4) (using CPU cores * 2 to exploit some buffering)
- 10.000 samples with a single user
- no-op action

## Running tests against your own system is simple too!
All you have to do is use the corresponding script located in /*_tests folder, remembering that the parameters are defined inline.
