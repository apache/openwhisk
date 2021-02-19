public class Empty {
    // Workaround for this issue https://github.com/akka/akka-grpc/issues/289
    // Gradle complains about no java sources.
    // Note. Openwhisk is using a lower gradle version, so the latest akka-grpc version cannot be used.
}
