import {Alert, Card, Divider, Typography} from 'antd';
import React from 'react';
import {Page} from '@jiangood/open-admin';

const {Title, Paragraph, Text, Link} = Typography;

/**
 * 隧道说明页。
 *
 * 平台不再登记客户端，也不再通过管理 API 下发代理规则：
 * http-tunnel 已经迭代到 2.x，自带 Web 管理端，代理（[[proxies]]）直接在客户端管理，
 * 这里只保留入口菜单和一份部署 / 使用说明，避免平台侧的版本兼容负担。
 */
const Code = ({children}) => (
    <pre style={{
        margin: '8px 0 16px',
        padding: '12px 16px',
        background: '#f6f8fa',
        borderRadius: 6,
        overflowX: 'auto',
        fontSize: 13,
        lineHeight: 1.6,
    }}>{children}</pre>
);

export default class extends React.Component {

    render() {
        return <Page padding>
            <Card className='page-card'>
                <Alert type='info' showIcon className='mb-3'
                       title='隧道由 http-tunnel 自行管理'
                       description={'平台不再登记客户端、不再调用管理 API 下发代理规则。'
                           + '请部署最新版 http-tunnel（ht），使用它自带的 Web 管理端维护 [[proxies]]；本页只说明部署与使用方式。'}/>

                <Typography>
                    <Title level={4}>1. 部署 ht</Title>
                    <Paragraph>
                        从 <Link href='https://github.com/jiangood/http-tunnel/releases' target='_blank' rel='noreferrer'>Releases</Link> 下载
                        <Text code>ht</Text>，或直接使用镜像 <Text code>ghcr.io/jiangood/ht:latest</Text>。
                        入口、中继、客户端都是同一个程序，角色只由 <Text code>ht.toml</Text> 是否包含
                        <Text code>[upstream]</Text> 决定。
                    </Paragraph>
                    <Code>{`docker run -d --name ht --restart unless-stopped \\
  --stop-timeout 40 \\
  -p 7777:7777 \\
  -v /opt/ht:/app \\
  ghcr.io/jiangood/ht:latest`}</Code>
                    <Paragraph type='secondary'>
                        容器固定读取 <Text code>/app/ht.toml</Text>；保存管理端修改需要挂载目录可写。
                        <Text code>--stop-timeout 40</Text> 让 ht 在停止时最多 drain 30 秒，避免截断在途请求。
                    </Paragraph>

                    <Divider/>

                    <Title level={4}>2. 编写入口配置</Title>
                    <Paragraph>
                        下面是最简单的单节点入口（入口和业务服务在同一台机器）。Token 可用
                        <Text code>openssl rand -hex 16</Text> 生成。
                    </Paragraph>
                    <Code>{`name = "edge"
listen_port = 7777
control_host = "control.example.com"
token = "把 openssl rand -hex 16 的输出填在这里"

[[proxies]]
name = "app"
domain = "app.example.com"
local_addr = "127.0.0.1:8080"`}</Code>
                    <Paragraph>
                        把 <Text code>app.example.com</Text> 的 A 记录指向入口 IP，访问
                        <Text code>http://app.example.com:7777</Text>。公网部署请在入口前加
                        Nginx / Caddy 等 TLS 终止层，把 443 反代到 <Text code>127.0.0.1:7777</Text>，
                        并转发 <Text code>Upgrade</Text> / <Text code>Connection</Text> 头。
                    </Paragraph>

                    <Divider/>

                    <Title level={4}>3. 用 Web 管理端维护代理</Title>
                    <Paragraph>
                        浏览器打开 <Text code>http://&lt;control_host&gt;:&lt;listen_port&gt;/</Text>
                        （例如 <Text code>http://control.example.com:7777/</Text>），
                        HTTP Basic 用户名填 <Text code>ht</Text>，密码填 <Text code>ht.toml</Text> 里的
                        <Text code>token</Text>。管理页、管理 API 和访客流量共用 <Text code>listen_port</Text>，
                        且只在 <Text code>control_host</Text> 上可达。
                    </Paragraph>
                    <Paragraph>
                        在管理页里可以直接增删改本地 <Text code>[[proxies]]</Text>：
                        <Text code>name</Text>（进程内唯一）、<Text code>domain</Text>（支持
                        <Text code>*.example.com</Text> 泛域名）、<Text code>local_addr</Text>
                        （原始 TCP 地址或 Unix Socket），按需设置 <Text code>auth_header</Text>。
                        修改立即生效并原子写回 <Text code>ht.toml</Text>，无需重启。
                    </Paragraph>
                    <Paragraph type='secondary'>
                        如果 <Text code>control_host</Text> 暂时没有解析到入口，可先用
                        <Text code>curl -H "Host: control.example.com" http://127.0.0.1:7777/</Text>
                        验证，或在本机 hosts 里补一条解析后再用浏览器打开。
                    </Paragraph>

                    <Divider/>

                    <Title level={4}>4. 内网节点通过 [upstream] 接入</Title>
                    <Paragraph>
                        内网机器不能开放入站端口时，让客户端主动拨出入口（两个节点），
                        或在中间再加一层中继（三个节点）。客户端配置示例：
                    </Paragraph>
                    <Code>{`name = "home"
listen_port = 0

[[proxies]]
name = "app"
domain = "app.example.com"
local_addr = "127.0.0.1:8080"

[upstream]
url = "wss://control.example.com:443"
token = "与服务端 token 相同"`}</Code>
                    <Paragraph>
                        客户端启动后会把本地 <Text code>[[proxies]]</Text> 镜像到上游，入口的 Web 管理端
                        可以在线管理已连接下游节点的代理。多跳时每一跳都配置 <Text code>[upstream]</Text>，
                        建议每跳使用不同的 Token；启动顺序为 服务端 → 中继 → 客户端。
                    </Paragraph>

                    <Divider/>

                    <Title level={4}>5. 在平台里配合使用</Title>
                    <Paragraph>
                        <ul style={{margin: '8px 0 16px', paddingLeft: 20}}>
                            <li>
                                <b>暴露应用：</b><Text code>local_addr</Text> 填应用映射到主机侧的端口
                                （如 <Text code>127.0.0.1:8080</Text>），<Text code>domain</Text> 填对外域名，
                                域名解析到入口即可。
                            </li>
                            <li>
                                <b>访问远端 Docker：</b><Text code>local_addr</Text> 填
                                <Text code>unix:///var/run/docker.sock</Text>（ht 以容器运行时需挂载该 socket），
                                并给这条代理配置 <Text code>auth_header</Text>。然后在平台「主机管理 → 连接方式 TCP →
                                自定义请求头」填名称 <Text code>X-HTTP-TUNNEL-TOKEN</Text>、值填密钥。
                            </li>
                            <li>
                                <b>容器里的地址：</b>ht 以容器运行时，<Text code>127.0.0.1</Text>
                                指容器自己；转发宿主机服务用 <Text code>host.docker.internal:&lt;端口&gt;</Text>，
                                转发同 compose 服务用服务名。
                            </li>
                        </ul>
                    </Paragraph>

                    <Divider/>

                    <Title level={4}>6. 常见问题</Title>
                    <Paragraph>
                        <ul style={{margin: '8px 0', paddingLeft: 20}}>
                            <li><Text code>404 No tunnel for this host</Text>：节点未连接，或域名不在任何
                                <Text code>[[proxies]]</Text> 中。</li>
                            <li><Text code>401</Text>：配置了 <Text code>auth_header</Text> 但访客缺少或带错
                                <Text code>X-HTTP-TUNNEL-TOKEN</Text>；多跳时也检查两端的 Token 是否一致。</li>
                            <li><Text code>502</Text>：隧道已匹配，但背后的本地服务连不上，检查
                                <Text code>local_addr</Text> 是否从 ht 所在环境可达。</li>
                            <li><Text code>control_host</Text> 不能与任何 <Text code>[[proxies]].domain</Text>
                                相同，也不能是泛域名。</li>
                            <li><Text code>listen_port = 0</Text> 只允许在有 <Text code>[upstream]</Text> 时使用，
                                实际端口见启动日志。</li>
                        </ul>
                    </Paragraph>

                    <Paragraph type='secondary' className='mt-3'>
                        完整配置项、多跳拓扑和排查说明见
                        <Link href='https://github.com/jiangood/http-tunnel' target='_blank' rel='noreferrer'> http-tunnel 项目文档</Link>。
                        版本迭代较快，请以该文档和 Releases 说明为准。
                    </Paragraph>
                </Typography>
            </Card>
        </Page>
    }
}
