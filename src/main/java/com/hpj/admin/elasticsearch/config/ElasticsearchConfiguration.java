package com.hpj.admin.elasticsearch.config;

import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;


@Configuration
public class ElasticsearchConfiguration {

    @Bean
    public BulkProcessor asyncBulkProcessor(RestHighLevelClient client) {
        return BulkProcessor.builder(((bulkRequest, bulkResponseActionListener) ->
                client.bulkAsync(bulkRequest, RequestOptions.DEFAULT, bulkResponseActionListener)), new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                List<DocWriteRequest<?>> requests = request.requests();
                for (DocWriteRequest<?> docWriteRequest : requests) {
                    System.out.println(docWriteRequest.id());
                }
                request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
                System.out.println("开始插入数据：" + request.getIndices());
                // 批量请求之前，进行处理
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                System.out.println("插入数据成功：" + request.getIndices());
                // 批量请求之后，进行处理
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                failure.printStackTrace();
                System.out.println("发生错误：" + request.getIndices());
                // 发生错误时，进行处理
            }
        }).setBulkSize(new ByteSizeValue(5, ByteSizeUnit.MB))  // 达到指定大小时，flush bulkProcessor里面全部数据
                .setBulkActions(1000) // 达到指定条数，flush bulkProcessor里面全部数据
                .setConcurrentRequests(10) // 并发请求数
                .setFlushInterval(TimeValue.timeValueSeconds(5)) // 固定时间，flush bulkProcessor里面全部数据
                .setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(100), 3)) // 重试策略,每100s重试一次，最多重试3次
                .build();
    }
}
