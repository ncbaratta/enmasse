FROM enmasseproject/java-base:8-11

ARG version
ARG maven_version
ARG commit
ENV VERSION=${version} COMMIT=${commit} MAVEN_VERSION=${maven_version}

ADD target/iot-http-adapter-${MAVEN_VERSION}.jar /iot-http-adapter.jar

CMD ["/opt/run-java/launch_java.sh", "/iot-http-adapter.jar"]
