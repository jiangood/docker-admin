package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.lang.Dict;
import io.github.jiangood.docker.admin.entity.AppGroup;
import io.github.jiangood.docker.admin.service.AppGroupService;
import io.github.jiangood.sa.common.dto.AjaxResult;
import io.github.jiangood.sa.common.dto.antd.Option;
import io.github.jiangood.sa.framework.config.argument.RequestBodyKeys;
import io.github.jiangood.sa.framework.data.domain.BaseEntity;
import io.github.jiangood.sa.framework.data.specification.Spec;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("admin/appGroup")
public class AppGroupController  {

    @Resource
    private AppGroupService service;

    @PreAuthorize("hasAuthority('appGroup:view')")
    @RequestMapping("page")
    public AjaxResult page(String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) throws Exception {
        Spec<AppGroup> q = Spec.of();
        q.like(AppGroup.Fields.name, searchText);

        Page<AppGroup> page = service.findPageByRequest(q, pageable);

        return AjaxResult.ok().data(page);
   }

    @RequestMapping("options")
    public AjaxResult options() throws Exception {
        Spec<AppGroup> q = Spec.of();

        List<AppGroup> list = service.findAll(q, Sort.by("seq"));


        List<Option> options = Option.convertList(list, BaseEntity::getId, AppGroup::getName);


        return AjaxResult.ok().data(options);
    }
    @RequestMapping("menus")
    public AjaxResult menus() {
        Spec<AppGroup> q = Spec.of();

        List<AppGroup> list = service.findAll(q, Sort.by("seq"));


        List<Dict> menus= list.stream().map(t -> Dict.of("key", t.getId(), "label", t.getName())).toList();


        return AjaxResult.ok().data(menus);
    }



    @PreAuthorize("hasAuthority('appGroup:save')")
    @PostMapping("save")
    public AjaxResult save(@RequestBody AppGroup input, RequestBodyKeys updateFields) throws Exception {
        service.saveOrUpdateByRequest(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }


    @PreAuthorize("hasAuthority('appGroup:delete')")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteByRequest(id);
        return AjaxResult.ok().msg("删除成功");
    }

}

