import React from 'react';
import {createRoot} from 'react-dom/client';
import routes from 'virtual:open-admin/routes';
import {registerRoutes} from '@jiangood/open-admin';
import App from './layouts';

registerRoutes(routes);
createRoot(document.getElementById('root')).render(<App/>);