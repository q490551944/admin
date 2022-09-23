package com.hpj.admin.elasticsearch.service;

import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchService {

    private final RestHighLevelClient client;

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    public ElasticsearchService(RestHighLevelClient client) {
        this.client = client;
    }

    /**
     * 创建es索引
     * @param request  创建索引请求
     * @return         是否创建成功
     */
    public boolean createIndex(CreateIndexRequest request) {
        try {
            CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
            return true;
        } catch (Exception e) {
            logger.error("创建索引失败：" + e);
            return false;
        }
    }

    /**
     * 刷新es索引
     * @param request 刷新索引请求
     * @return        是否刷新成功
     */
    public boolean refreshIndex(RefreshRequest request) {
        try {
            RefreshResponse refresh = client.indices().refresh(request, RequestOptions.DEFAULT);
            return true;
        } catch (Exception e) {
            logger.error("刷新索引失败：" + e);
            return false;
        }


    }
}
