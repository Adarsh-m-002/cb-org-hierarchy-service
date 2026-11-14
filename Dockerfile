FROM eclipse-temurin:17-jdk-jammy

RUN useradd -ms /bin/bash appuser

RUN apt-get update && \
    apt-get install -y \
        curl \
        libxrender1 \
        libjpeg-turbo8 \
        fontconfig \
        libxtst6 \
        xfonts-75dpi \
        xfonts-base \
        xz-utils && \
    rm -rf /var/lib/apt/lists/*

COPY cb-org-hierarchy-service-0.0.1-SNAPSHOT.jar /opt/

RUN chown -R appuser:appuser /opt

USER appuser
WORKDIR /opt

#HEALTHCHECK --interval=30s --timeout=30s CMD curl --fail http://localhost:7001/actuator/health || exit 1

# cloud-store-sdk's jclouds path (Guice 3.0's own CGLIB at startup, reflective java.net.URI construction on every
# upload/download) needs these opens on Java 17 - baked into the image's own launch command (not left to
# $JAVA_OPTIONS/the Helm chart's env.javaoptions) so this never depends on a deployment remembering to set them;
# an IDE run configuration still needs the same options added manually, same as this.
CMD ["/bin/bash", "-c", "java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED -XX:+PrintFlagsFinal $JAVA_OPTIONS -XX:+UnlockExperimentalVMOptions -jar /opt/cb-org-hierarchy-service-0.0.1-SNAPSHOT.jar"]
