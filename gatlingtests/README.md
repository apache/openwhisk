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
