import {defineConfig} from 'umi';
import config from "@jiangood/springboot-admin-starter/config/dist/config";

config. proxy= {

    '/admin': {
        target: 'http://127.0.0.1:8002',
            changeOrigin: true,
    },
    '/admin/ws': {
        target: 'http://127.0.0.1:8002',
        changeOrigin: true,
        ws: true,
    },
}

console.log('配置',config)

export default defineConfig(config);
