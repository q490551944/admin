create table user(
    `id` bigint(20) comment '主键ID' primary key ,
    `username` varchar(100) comment '用户名',
    `password` varchar(100) comment '密码',
    `status` bit(1) comment '状态',
    `sex` bit(1) comment '性别'
    )