package com.hpj.admin.service.impl;

import com.hpj.admin.common.annotation.GroovyFunction;
import org.springframework.stereotype.Service;

@Service
@GroovyFunction
public class TestService {
    public String testQuery(long id) {
        return "Test query success, id is " + id;
    }
}
