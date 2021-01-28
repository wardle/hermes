FROM clojure
RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
COPY deps.edn /usr/src/app/deps.edn
RUN clj -Spom
COPY . /usr/src/app
RUN clojure -X:uberjar
RUN ls target
RUN mkdir -p /db/
CMD ["java", "-jar", "./target/hermes-full-v0.1.0.jar", "-d", "/db/snomed.db", "-p", "8080", "serve"]