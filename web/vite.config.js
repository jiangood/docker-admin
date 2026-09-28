import {defineConfig, loadEnv} from 'vite';
import react from '@vitejs/plugin-react';
import openAdmin from '@jiangood/open-admin/vite-plugin';

export default defineConfig(({mode, command}) => {
    const env = loadEnv(mode, process.cwd(), '');
    const servletContext = env.VITE_SERVER_SERVLET_CONTEXT_PATH;
    const serverPort = env.SERVER_PORT;
    const port = Number(env.PORT);
    console.log('前端端口' + port + ',后端端口' + serverPort + ',请求上下文' + servletContext)

    return {
        plugins: [react(), openAdmin()],
        base: command === 'build' ? './' : '/',
        resolve: {
            dedupe: ['react', 'react-dom'],
        },
        optimizeDeps: {
            // 让 Vite 预扫描 open-admin 源码，自动发现并预构建其内部依赖（qs/lodash/prop-types 等 CJS 包）。
            // 缺少此项时，浏览器会拿到原始 CJS 模块，报 "does not provide an export named 'default'" 导致白屏。
            entries: [
                'index.html',
                './node_modules/@jiangood/open-admin/src/**/*.{ts,tsx,js,jsx}',
            ],
        },
        server: {
            port: port,
            proxy: {
                [servletContext]: {
                    target: `http://127.0.0.1:${serverPort}`,
                    changeOrigin: true,
                    ws: true,
                },
            },
        },
    };
});