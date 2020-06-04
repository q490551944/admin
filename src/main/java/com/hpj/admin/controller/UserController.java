package com.hpj.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpj.admin.common.annotation.Edit;
import com.hpj.admin.entity.BaseEntity;
import com.hpj.admin.entity.User;
import com.hpj.admin.mapper.UserMapper;
import com.hpj.admin.service.UserService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.Collections;
import java.util.List;

/**
 * @author huangpeijun
 * @date 2020/3/3
 */
@RestController
@RequestMapping("/sys/users")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private UserMapper userMapper;

    @GetMapping
    public List<User> query(@RequestParam long current,
                            @RequestParam long size) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("", "");
        Page<User> page = new Page<>(current, size);
        IPage<User> iPage = userMapper.selectPage(page, wrapper);
        List<User> list = userService.lambdaQuery().eq(BaseEntity::getDeleted, false).list();
        System.out.println(list);
        return iPage.getRecords();
    }

    @GetMapping("/one")
    public User one(@RequestParam Long id) {
        return userService.getById(id);
    }

    @PostMapping
    public User save(@RequestBody @Valid User user) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.having("username = {0}", user.getUsername());
        userService.save(user);
        return user;
    }

    @PutMapping
    public User update(@RequestBody @Validated({Edit.class}) User user) {
        userService.updateById(user);
        return user;
    }

    @DeleteMapping
    public User delete(String ids) {
        List<String> strings = Collections.singletonList(ids);
        userMapper.deleteBatchIds(strings);
        return null;
    }

    @DeleteMapping("/{id}")
    public void j(@PathVariable String id) {
        System.out.println(id);
    }


}
