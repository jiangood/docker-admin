import React from 'react';
import {createRoot} from 'react-dom/client';
import routes from 'virtual:open-admin/routes';
import {registerRoutes} from '@jiangood/open-admin';
import App from './layouts';
import './index.css';

registerRoutes(routes);
createRoot(document.getElementById('root')).render(<App/>);