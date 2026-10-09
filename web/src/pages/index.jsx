import {Card, Typography} from 'antd'
import {Page} from '@jiangood/open-admin'

export default function () {
    return <Page padding>
        <Card className='page-card'>
            <Typography.Title level={4} style={{marginTop: 0}}>欢迎来到 Docker Admin</Typography.Title>
            <Typography.Text type='secondary'>版本 v{__APP_VERSION__}</Typography.Text>
        </Card>
    </Page>
}
