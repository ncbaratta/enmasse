FROM enmasseproject/java-base:8-11

ARG version
ARG maven_version
ARG commit
ENV VERSION=${version} COMMIT=${commit} MAVEN_VERSION=${maven_version}

ADD target/iot-device-registry-${MAVEN_VERSION}.jar /iot-device-registry.jar

CMD ["/opt/run-java/launch_java.sh", "/iot-device-registry.jar"]
