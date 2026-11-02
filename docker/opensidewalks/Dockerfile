# OpenTripPlanner with the OpenSidewalks sandbox feature and the AccessMap web app, in one image.
# The data is named when the container starts, see README.md.
#
# Build from the root of this repository:
#   docker build -f docker/opensidewalks/Dockerfile \
#     --build-context accessmap=https://github.com/TaskarCenterAtUW/AccessMapWebAppOTP.git#main \
#     -t otp-accessmap .

########## OTP ##########
FROM maven:3-eclipse-temurin-25 AS otp
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --quiet package -DskipTests -Dps -Dcs -Dmaven.gitcommitid.skip=true \
      --projects otp-shaded --also-make \
 && cp "$(ls -S otp-shaded/target/otp-shaded-*.jar | head -1)" /otp.jar \
 && (cd /tmp && jar xf /otp.jar META-INF/MANIFEST.MF && grep -q Main-Class META-INF/MANIFEST.MF)

########## AccessMap ##########
# node-sass does not build on this Node, so Dart Sass replaces it, as the README of the app says.
FROM node:20-bookworm-slim AS ui
WORKDIR /src
COPY --from=accessmap . .
RUN npm install --legacy-peer-deps --ignore-scripts --no-audit --no-fund \
 && npm install --legacy-peer-deps --no-save --no-audit --no-fund sass@1.32.13 \
 && rm -rf node_modules/node-sass \
 && NODE_OPTIONS=--openssl-legacy-provider npm run build \
 && cp src/favicon.ico public/ \
 && test -f public/index.bundle.js

########## runtime ##########
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends nginx-light python3 tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 otp \
 && mkdir -p /var/opentripplanner && chown otp /var/opentripplanner

COPY --from=otp /otp.jar /app/otp.jar
COPY --from=ui /src/public /app/ui
COPY application/src/ext-test/resources/org/opentripplanner/ext/opensidewalks/ws-prod.json /app/ws-prod.json
COPY docker/opensidewalks/start.py docker/opensidewalks/nginx.conf /app/
RUN chmod -R a+rX /app

ENV JAVA_OPTS=-XX:MaxRAMPercentage=75 \
    PYTHONUNBUFFERED=1

USER otp
VOLUME /var/opentripplanner
EXPOSE 8080
# Healthy once OTP answers, which is after the graph has been built on the first start.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30m \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/otp/', timeout=4)"

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python3", "/app/start.py"]
