package io.github.jiangood;

import io.github.jiangood.as.common.tools.jdbc.DbTool;
import io.github.jiangood.docker.admin.dao.AppDao;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.AppGroup;
import lombok.AllArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@AllArgsConstructor
@Component
public class DataInit implements CommandLineRunner {

    DbTool db;

    AppDao appDao;


    @Override
    @Transactional
    public void run(String... args) throws Exception {
        List<App> list = appDao.findByTagIsNull();
        for (App app : list) {
            AppGroup appGroup = app.getAppGroup();
            if(appGroup != null){
                if(!appGroup.getName().equals("默认分组")){
                    app.setTag(appGroup.getName());
                }

            }

        }

    }
}
