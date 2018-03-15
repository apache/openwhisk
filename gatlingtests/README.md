# Gatling Tests

These tests can be used to test the performance of a deployed OpenWhisk-system.

## Simulations

You can specify two thresholds for the simulations.
The reason is, that Gatling is able to handle each assertion as a JUnit test.
On using CI/CD pipelines (e.g. Jenkins) you will be able to set a threshold on an amount of failed testcases to mark the build as stable, unstable and failed.

### ApiV1Simulation

This Simulation calls the `api/v1`.
You can specify the endpoint, the amount of connections against the backend and the duration of this burst.

Available environment variables:

```
OPENWHISK_HOST          (required)
CONNECTIONS             (required)
SECONDS                 (default: 10)
REQUESTS_PER_SEC        (required)
MIN_REQUESTS_PER_SEC    (default: REQUESTS_PER_SEC)
```

You can run the simulation with
```
OPENWHISK_HOST="openwhisk.mydomain.com" CONNECTIONS="10" REQUESTS_PER_SEC="50" ./gradlew gatlingRun-ApiV1Simulation
```

### BlockingInvokeOneActionSimulation

This simulation executes the same action with the same user over and over again.
The aim of this test is, to test the throughput of the system, if all containers are always warm.

The action that is invoked, writes one log line and returns a little json.

The simulations creates the action in the beginning, invokes it as often as possible for 5 seconds, to warm all containers up and invokes it afterwards for the given amount of time.
The warmup-phase will not be part of the assertions.

To run the test, you can specify the amount of concurrent requests. Keep in mind, that the actions are invoked blocking and the system is limited to `AMOUNT_OF_INVOKERS * SLOTS_PER_INVOKER * NON_BLACKBOX_INVOKER_RATIO` concurrent actions/requests.

Available environment variables:
```
OPENWHISK_HOST          (required)
CONNECTIONS             (required)
SECONDS                 (default: 10)
REQUESTS_PER_SEC        (required)
MIN_REQUESTS_PER_SEC    (default: REQUESTS_PER_SEC)
```

You can run the simulation with
```
OPENWHISK_HOST="openwhisk.mydomain.com" CONNECTIONS="10" REQUESTS_PER_SEC="50" ./gradlew gatlingRun-BlockingInvokeOneActionSimulation
```
