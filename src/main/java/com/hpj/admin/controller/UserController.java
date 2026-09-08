package com.hpj.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpj.admin.entity.User;
import com.hpj.admin.enums.Sex;
import com.hpj.admin.mapper.UserMapper;
import com.hpj.admin.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * @author huangpeijun
 * @date 2020/3/3
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/sys/users")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private UserMapper userMapper;


    @GetMapping
    @Operation(summary = "查询用户信息")
    @Parameters({
            @Parameter(name = "current", description = "当前页，默认 1"),
            @Parameter(name = "size", description = "每页数量，默认 20")
    })
    public List<User> query(@RequestParam(defaultValue = "1") long current,
                            @RequestParam(defaultValue = "20") long size) {
        requirePositive(current, "current");
        requirePositive(size, "size");
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("status", true);
        Page<User> page = new Page<>(current, size);
        IPage<User> iPage = userMapper.selectPage(page, wrapper);
        return iPage.getRecords();
    }


    @GetMapping("/{id}")
    @Operation(summary = "查询单个用户")
    public User one(@PathVariable Long id) {
        requirePositive(id, "id");
        User user = userService.getById(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return user;
    }

    @PostMapping
    @Operation(summary = "创建用户")
    public ResponseEntity<User> save(@RequestBody @Valid User user) {
        if (user.getId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创建用户时不应指定 id");
        }
        if (!userService.save(user)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "用户创建失败");
        }
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(user.getId()).toUri()).body(user);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "部分更新用户")
    public void update(@PathVariable Long id, @RequestBody @Valid UpdateUser request) {
        requirePositive(id, "id");
        if (request.id() != null && !id.equals(request.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体 id 必须与路径一致");
        }
        if (request.username() == null && request.password() == null && request.status() == null && request.sex() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少提供一个需要更新的字段");
        }
        User user = new User();
        user.setId(id);
        user.setUsername(request.username());
        user.setPassword(request.password());
        user.setStatus(request.status());
        user.setSex(request.sex());
        if (!userService.updateById(user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "批量删除用户")
    public void delete(@RequestParam List<Long> ids) {
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 不能为空");
        ids.forEach(id -> requirePositive(id, "ids"));
        userMapper.deleteBatchIds(ids);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除单个用户")
    public void deleteOne(@PathVariable Long id) {
        requirePositive(id, "id");
        if (!userService.removeById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    public record UpdateUser(Long id,
            @Pattern(regexp = "^[A-Za-z][\\w]{0,19}$") String username,
            @Pattern(regexp = "[\\w]{1,20}$") String password,
            Boolean status, Sex sex) {}

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 必须为正整数");
        }
    }
}
