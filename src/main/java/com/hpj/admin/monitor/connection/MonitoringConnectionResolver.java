package com.hpj.admin.monitor.connection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.mongodb.client.MongoClient;
import org.elasticsearch.client.RestClient;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.*;
import static com.hpj.admin.monitor.connection.ResolvedTarget.Reason;

/** Resolves explicit target declarations from existing singletons, without retrieving lazy beans. */
public final class MonitoringConnectionResolver {
    private final MonitoringProperties properties;
    private final ConfigurableListableBeanFactory beans;
    private final List<ConnectionInspector> inspectors;

    public MonitoringConnectionResolver(MonitoringProperties properties, ConfigurableListableBeanFactory beans,
                                        List<ConnectionInspector> inspectors) {
        properties.validate();
        this.properties = properties;
        this.beans = beans;
        this.inspectors = List.copyOf(inspectors);
    }

    public List<ResolvedTarget> resolve() {
        Map<String, Object> existing = new LinkedHashMap<>();
        if (properties.isEnabled() && properties.getTargets().stream().anyMatch(MonitoringProperties.Target::isEnabled)) {
            // getSingleton() does not create a lazy bean or dereference a FactoryBean.
            for (String name : beans.getSingletonNames()) {
                if (!MonitoringProperties.safeReference(name)) continue;
                Object singleton = beans.getSingleton(name);
                if (singleton != null && !(singleton instanceof FactoryBean<?>) && !AopUtils.isAopProxy(singleton)) {
                    existing.put(name, singleton);
                }
            }
        }
        Map<String, Optional<ResolvedConnection>> inspected = new LinkedHashMap<>();
        List<ResolvedTarget> resolved = properties.getTargets().stream()
                .sorted(Comparator.comparing(MonitoringProperties.Target::getId))
                .map(target -> resolve(target, existing, inspected)).toList();
        return mergeRedis(resolved);
    }

    private ResolvedTarget resolve(MonitoringProperties.Target target, Map<String, Object> existing,
                                    Map<String, Optional<ResolvedConnection>> inspected) {
        if (!properties.isEnabled() || !target.isEnabled()) return unresolved(target, Reason.DISABLED);
        String declared = target.getConnectionSource();
        if (declared == null || declared.isBlank()) return unresolved(target, Reason.SOURCE_MISSING);
        List<String> candidates;
        if (existing.containsKey(declared)) {
            candidates = List.of(declared);
        } else {
            Predicate<Object> predicate = alias(declared, target.getType(), existing);
            if (predicate == null) return unresolved(target, Reason.SOURCE_MISSING);
            candidates = existing.entrySet().stream().filter(entry -> predicate.test(entry.getValue()))
                    .map(Map.Entry::getKey).sorted().toList();
            if (candidates.size() > 1) {
                List<String> primary = candidates.stream().filter(this::isPrimary).toList();
                if (primary.size() == 1) candidates = primary;
            }
        }
        if (candidates.isEmpty()) return unresolved(target, Reason.SOURCE_MISSING);
        if (candidates.size() > 1) return unresolved(target, Reason.AMBIGUOUS_SOURCE);
        String name = candidates.get(0);
        Object singleton = existing.get(name);
        if (!supports(target.getType(), singleton)) return unresolved(target, Reason.UNSUPPORTED_SOURCE);
        Optional<ResolvedConnection> connection = inspected.computeIfAbsent(name, key -> inspect(name, singleton));
        if (connection.isEmpty() || connection.get().type() != target.getType()) {
            return unresolved(target, Reason.UNSUPPORTED_SOURCE);
        }
        var binding = new ResolvedTarget.SourceBinding(target.getId(), declared, connection.get(), scope(target));
        MonitoringTarget display = new MonitoringTarget(target.getId(), target.getType(), name(target), CONFIGURED,
                List.of(connection.get().source()), binding.scope());
        return new ResolvedTarget(display, List.of(target.getId()), List.of(binding), Reason.CONFIGURED);
    }

    private Optional<ResolvedConnection> inspect(String name, Object singleton) {
        for (ConnectionInspector inspector : inspectors) {
            try {
                Optional<ResolvedConnection> result = inspector.inspect(name, singleton);
                if (result.isPresent()) return result;
            } catch (RuntimeException ignored) {
                // Metadata errors may contain credentials. Only the stable unavailable reason is exposed.
            }
        }
        return Optional.empty();
    }

    private boolean isPrimary(String name) {
        return beans.containsBeanDefinition(name) && beans.getBeanDefinition(name).isPrimary();
    }

    private static Predicate<Object> alias(String source, MiddlewareType type, Map<String, Object> existing) {
        if (type == MiddlewareType.MYSQL && source.equals("spring.datasource")) return DataSource.class::isInstance;
        if (type == MiddlewareType.KAFKA && source.equals("spring.kafka")) return KafkaAdmin.class::isInstance;
        if (type == MiddlewareType.MONGODB && source.equals("spring.data.mongodb")) return MongoClient.class::isInstance;
        if (type == MiddlewareType.REDIS && source.equals("redisConnectionFactory")) return RedisConnectionFactory.class::isInstance;
        if (type == MiddlewareType.REDIS && source.equals("redisson")) return RedissonClient.class::isInstance;
        if (type == MiddlewareType.ELASTICSEARCH && source.equals("spring.elasticsearch")) {
            return existing.values().stream().anyMatch(ElasticsearchClient.class::isInstance)
                    ? ElasticsearchClient.class::isInstance : RestClient.class::isInstance;
        }
        if (type == MiddlewareType.MINIO && source.equals("chat.attachment")) return ChatProperties.class::isInstance;
        return null;
    }

    private static boolean supports(MiddlewareType type, Object instance) {
        return switch (type) {
            case MYSQL -> instance instanceof DataSource;
            case REDIS -> instance instanceof RedisConnectionFactory || instance instanceof RedissonClient;
            case KAFKA -> instance instanceof KafkaAdmin || instance instanceof ProducerFactory<?, ?> || instance instanceof ConsumerFactory<?, ?>;
            case MONGODB -> instance instanceof MongoClient;
            case ELASTICSEARCH -> instance instanceof ElasticsearchClient || instance instanceof RestClient;
            case MINIO -> instance instanceof ChatProperties;
        };
    }

    private static ResolvedTarget unresolved(MonitoringProperties.Target target, Reason reason) {
        String declared = target.getConnectionSource();
        MonitoringTarget display = new MonitoringTarget(target.getId(), target.getType(), name(target),
                reason == Reason.DISABLED ? DISABLED : CONFIGURATION_MISSING,
                declared == null || declared.isBlank() ? List.of() : List.of(declared), scope(target));
        return new ResolvedTarget(display, List.of(target.getId()), List.of(), reason);
    }

    private static String name(MonitoringProperties.Target target) {
        return target.getName() == null || target.getName().isBlank() ? target.getType().displayName() : target.getName();
    }

    private static MonitoringTarget.Scope scope(MonitoringProperties.Target target) {
        var scope = target.getScope();
        return new MonitoringTarget.Scope(scope.getDatabases(), scope.getTopics(), scope.getConsumerGroups(),
                scope.getBuckets(), scope.getIndices());
    }

    private static List<ResolvedTarget> mergeRedis(List<ResolvedTarget> targets) {
        List<List<ResolvedTarget>> groups = new ArrayList<>();
        for (ResolvedTarget target : targets) {
            List<List<ResolvedTarget>> matches = groups.stream()
                    .filter(group -> group.stream().anyMatch(member -> sameRedis(member, target))).toList();
            List<ResolvedTarget> group = new ArrayList<>();
            for (List<ResolvedTarget> match : matches) { group.addAll(match); groups.remove(match); }
            group.add(target);
            groups.add(group);
        }
        return groups.stream().map(MonitoringConnectionResolver::combine)
                .sorted(Comparator.comparing(target -> target.display().id())).toList();
    }

    private static boolean sameRedis(ResolvedTarget left, ResolvedTarget right) {
        return left.display().type() == MiddlewareType.REDIS && right.display().type() == MiddlewareType.REDIS
                && left.reason() == Reason.CONFIGURED && right.reason() == Reason.CONFIGURED
                && left.bindings().get(0).connection().identity().sameAs(right.bindings().get(0).connection().identity());
    }

    private static ResolvedTarget combine(List<ResolvedTarget> members) {
        if (members.size() == 1) return members.get(0);
        members.sort(Comparator.comparing(member -> member.display().id()));
        MonitoringTarget first = members.get(0).display();
        var bindings = members.stream().flatMap(member -> member.bindings().stream()).toList();
        List<String> sources = bindings.stream().map(binding -> binding.connection().source()).distinct().sorted().toList();
        List<String> ids = members.stream().flatMap(member -> member.memberIds().stream()).distinct().sorted().toList();
        // Display the combined declarations; collectors must still use each binding's own scope.
        var scopes = bindings.stream().map(ResolvedTarget.SourceBinding::scope).toList();
        MonitoringTarget.Scope summary = new MonitoringTarget.Scope(
                union(scopes.stream().map(MonitoringTarget.Scope::databases).toList()),
                union(scopes.stream().map(MonitoringTarget.Scope::topics).toList()),
                union(scopes.stream().map(MonitoringTarget.Scope::consumerGroups).toList()),
                union(scopes.stream().map(MonitoringTarget.Scope::buckets).toList()),
                union(scopes.stream().map(MonitoringTarget.Scope::indices).toList()));
        return new ResolvedTarget(new MonitoringTarget(first.id(), first.type(), first.name(), CONFIGURED, sources, summary),
                ids, bindings, Reason.CONFIGURED);
    }

    private static List<String> union(List<List<String>> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(result::addAll);
        return List.copyOf(result);
    }
}
