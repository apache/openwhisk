Release Notes
=============

## latest - running releases of `latest` tag

No changes yet.

## 0.10.2.1 - 12 June, 2017

### Added

- Allow configuration of `num.partitions` ([sjking], #40)
- Allow configuration of `log.retention.hours` (#42)

### Changed

- Update to Kafka 0.10.2.1

### Fixed

- Fixed incorrect variable name substitution for `KAFKA_AUTO_CREATE_TOPICS_ENABLE` (#43)

## 0.10.2.0 - 31 March, 2017

- Update to Kafka 0.10.2.0 ([bgaechter], #39)
- Switch to Scala 2.12 builds as now recommended by Kafka.
- Change config var `GROUP_MAX_SESSION_TIMEOUT_MS` to
  `KAFKA_GROUP_MAX_SESSION_TIMEOUT_MS` for consistency.
- Allow configuration of `auto.create.topics.enable`,
  `inter.broker.protocol.version`, and `log.message.format.version`
  ([bgaechter], #39)

## 0.10.1.1 - 31 March, 2017

- Update to Kafka 0.10.1.1
- Allow configuration of `default.replication.factor` ([sjking], #32)

## 0.10.1.0 - 27 October, 2016

- Update to Kafka 0.10.1.0 ([xrl], #25)

## 0.10.0.1 - 3 September, 2016

- Update to Kafka 0.10.0.1
- Make IP detection from `/etc/hosts` in the start script resilient to multiple
  or partial matches. ([Jamstah], #18)
- Add configurability for several timeout values. ([closedLoop], #20)

## 0.10.0.0 - 16 June, 2016

- Updated to Kafka 0.10.0.0
- Updated to Java 8

## 0.9.0.1 - 17 April, 2016

- Updated to Kafka 0.9.0.1

## 0.9.0.0 - 17 April, 2016

- Updated to Kafka 0.9.0.0. Switched to Scala 2.11 builds as now recommended by
  the project.

## 0.8.2.2 - 17 April, 2016

- Updated to Kafka 0.8.2.2
- Allow more flexible configuration of ZooKeeper connection string so that a ZK
  cluster can be used. ([androa], #4)
- Fix `advertised.host.name` for resolution for `/etc/hosts` changes in Docker
  1.10.0+. ([davidgiesberg], #14)

## 0.8.2.1 - 24 August, 2015

- Updated to Kafka 0.8.2.1
- Switch base image to `netflixoss/java:7`. `relateiq/oracle-java7` does not
  tag its images, which is rather annoying for build consistency, and further,
  they changed it to basing on `ubuntu:14.10` which is not a Long Term Support
  release. In my opinion non-LTS versions are not suitable for production
  server usage.
- Fix JMX connectivity by pegging RMI port.
- Cleaned up the `start.sh` script to remove RelateIQ dev particularities.
- Changed EXPOSE env var names to ADVERTISED to better match Kafka config
  properties.

## 0.8.1.1-1 - 4 September, 2014

- Adds /kafka/bin to PATH for more convenient use of tools like `kafka-topics.sh`
- Creates a `kafka` user to own the service process and data
- Fixes slf4j-log4j not loading--typo on adding jar to classpath

## 0.8.1.1

Initial build with Kafka 0.8.1.1 from official binary distribution.


[androa]: https://github.com/androa
[bgaechter]: https://github.com/bgaechter
[closedLoop]: https://github.com/closedLoop
[davidgiesberg]: https://github.com/davidgiesberg
[Jamstah]: https://github.com/Jamstah
[sjking]: https://github.com/sjking
[xrl]: https://github.com/xrl
