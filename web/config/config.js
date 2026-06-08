import {defineConfig} from 'umi';
import config from "@jiangood/open-admin/config/config";

const resolvedConfig = {
  ...config,
  plugins: config.plugins?.map(p =>
    p === './config/common-plugin'
      ? '@jiangood/open-admin/config/common-plugin'
      : p
  ),
};

export default defineConfig(resolvedConfig);
