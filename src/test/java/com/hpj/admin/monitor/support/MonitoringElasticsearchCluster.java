package com.hpj.admin.monitor.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/** Disposable two-node cluster; test-only settings and pause operations never touch external deployments. */
public final class MonitoringElasticsearchCluster implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final String cluster = "monitor-" + UUID.randomUUID();
    private final GenericContainer<?> first = node("owned-es-a");
    private final GenericContainer<?> second = node("owned-es-b");
    private boolean paused;

    private GenericContainer<?> node(String name) {
        return new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.10.4")
                .withNetwork(network).withNetworkAliases(name).withExposedPorts(9200)
                .withEnv("cluster.name", cluster).withEnv("node.name", name)
                .withEnv("discovery.seed_hosts", "owned-es-a,owned-es-b")
                .withEnv("cluster.initial_master_nodes", "owned-es-a")
                .withEnv("xpack.security.enabled", "false").withEnv("xpack.ml.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
                .withEnv("path.data", "/usr/share/elasticsearch/data/a,/usr/share/elasticsearch/data/b")
                .waitingFor(Wait.forHttp("/").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(2));
    }

    public static MonitoringElasticsearchCluster start() {
        var environment = new MonitoringElasticsearchCluster();
        try {
            environment.first.start(); environment.second.start();
            try (RestClient observer = environment.client()) {
                var json = new ObjectMapper();
                await().atMost(Duration.ofSeconds(45)).until(() -> {
                    var request = new Request("GET", "/_nodes/stats/process"); request.addParameter("timeout", "1s");
                    try (var body = observer.performRequest(request).getEntity().getContent()) {
                        return json.readTree(body).path("_nodes").path("successful").asInt() == 2;
                    }
                });
            }
            return environment;
        } catch (Exception failure) { environment.close(); throw new IllegalStateException("Owned Elasticsearch cluster did not start", failure); }
    }

    public RestClient client() {
        return RestClient.builder(new HttpHost(first.getHost(), first.getMappedPort(9200), "http"))
                .setRequestConfigCallback(config -> config.setConnectTimeout(3000).setSocketTimeout(3000).setConnectionRequestTimeout(3000)).build();
    }
    public void pauseSecond() { DockerClientFactory.instance().client().pauseContainerCmd(second.getContainerId()).exec(); paused = true; }
    public void resumeSecond() {
        if (paused) { DockerClientFactory.instance().client().unpauseContainerCmd(second.getContainerId()).exec(); paused = false; }
    }
    @Override public void close() {
        try { resumeSecond(); } finally {
            try { second.stop(); } finally { try { first.stop(); } finally { network.close(); } }
        }
    }
}
