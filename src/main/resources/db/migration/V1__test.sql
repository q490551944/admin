create table user
(
    id          bigint                              not null comment '用户ID',
    username    varchar(32)                         null comment '用户名',
    password    varchar(32)                         null comment '密码',
    status      bit                                 null comment '用户状态',
    create_time timestamp default CURRENT_TIMESTAMP null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP null comment '修改时间',
    constraint user_id_uindexsts
        unique (id)
);

alter table user
    add primary key (id);

create table role
(
    id          varchar(32)                         null comment '角色ID',
    role_name   varchar(32)                         null comment '角色名',
    create_time timestamp default CURRENT_TIMESTAMP null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP null comment '更新时间'
);

