import {Card, Typography} from 'antd'
import {Page} from '@jiangood/open-admin'

export default function () {
    return <Page padding>
        <Card className='page-card'>
            欢迎来到 Docker Admin
            <div style={{marginTop: 8}}>
                <Typography.Text type='secondary'>版本 v{__APP_VERSION__}</Typography.Text>
            </div>
        </Card>
    </Page>
}
