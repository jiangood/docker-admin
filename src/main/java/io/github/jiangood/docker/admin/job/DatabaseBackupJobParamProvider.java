package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.modules.job.JobDescription;
import io.github.jiangood.openadmin.modules.job.JobParamFieldProvider;
import io.github.jiangood.openadmin.util.field.Field;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「数据库备份」任务的参数：发送邮件时使用的代理。
 * <p>
 * 下拉数据复用「设置 - 代理管理」的选项接口；不选表示沿用框架系统参数里的全局邮件代理。
 */
@Component
public class DatabaseBackupJobParamProvider implements JobParamFieldProvider {

    public static final String PARAM_PROXY_ID = "proxyId";

    @Override
    public List<Field> getFields(JobDescription description, Map<String, Object> jobData) {
        Field proxy = new Field();
        proxy.setName(PARAM_PROXY_ID);
        proxy.setLabel("发送邮件代理");
        proxy.setPlaceholder("不使用代理");
        proxy.setValueType("select");
        proxy.setComponentProps(Map.of("url", "admin/proxy/options"));
        return List.of(proxy);
    }
}
