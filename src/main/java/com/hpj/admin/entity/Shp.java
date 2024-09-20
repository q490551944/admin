package com.hpj.admin.entity;

import lombok.Data;

import java.util.List;

@Data
public class Shp {

    private String name;

    private String fclass;

    private String theGeom;

    private List<String> positions;
}
