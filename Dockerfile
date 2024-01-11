FROM opensearchproject/opensearch:2.11.1
ADD ./plugins/api/build/distributions/api-2.11.1.zip /tmp/
COPY ./server/build/distributions/opensearch-2.11.1.jar /usr/share/opensearch/lib/
COPY ./libs/core/build/distributions/opensearch-core-2.11.1.jar /usr/share/opensearch/lib/
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch file:/tmp/api-2.11.1.zip

