package com.hpj.admin.elasticsearch.service;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchService {

    @Autowired
    private BulkProcessor bulkProcessor;

}
