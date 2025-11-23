from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS
import yaml

app = Flask(__name__, static_folder='static')
CORS(app)

CONFIG_PATH = '/app/config.yml'

@app.route('/')
def index():
    return send_from_directory('.', 'index.html')

@app.route('/api/config', methods=['GET'])
def get_config():
    try:
        with open(CONFIG_PATH, 'r') as f:
            data = yaml.safe_load(f)

        routes = []
        if 'config' in data and 'lite' in data['config'] and 'routes' in data['config']['lite']:
            for route in data['config']['lite']['routes']:
                host = route.get('host', '')
                backend = route.get('backend', '')

                if isinstance(host, list):
                    host = str(host)
                if isinstance(backend, list):
                    backend = str(backend)

                route_obj = {
                    'host': host,
                    'backend': backend,
                    'strategy': route.get('strategy', 'sequential'),
                    'cachePingTTL': route.get('cachePingTTL', '3m'),
                    'modifyVirtualHost': route.get('modifyVirtualHost', False),
                    'fallback': None
                }

                # Handle fallback
                if 'fallback' in route:
                    fallback = route['fallback']
                    route_obj['fallback'] = {
                        'motd': fallback.get('motd', ''),
                        'versionName': fallback.get('version', {}).get('name', ''),
                        'versionProtocol': fallback.get('version', {}).get('protocol', -1)
                    }

                routes.append(route_obj)

        return jsonify({
            'proxyProtocol': data.get('config', {}).get('proxyProtocol', True),
            'routes': routes
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/config', methods=['POST'])
def save_config():
    try:
        data = request.json

        routes = []
        for route in data.get('routes', []):
            host = route['host']
            backend = route['backend']

            # Parse array syntax
            if host.startswith('[') and host.endswith(']'):
                host = [h.strip() for h in host[1:-1].split(',')]
            if backend.startswith('[') and backend.endswith(']'):
                backend = [b.strip() for b in backend[1:-1].split(',')]

            route_config = {
                'host': host,
                'backend': backend
            }

            # Add optional fields only if not default
            if route.get('strategy') and route['strategy'] != 'sequential':
                route_config['strategy'] = route['strategy']

            if route.get('cachePingTTL') and route['cachePingTTL'] != '3m':
                route_config['cachePingTTL'] = route['cachePingTTL']

            if route.get('modifyVirtualHost'):
                route_config['modifyVirtualHost'] = True

            # Handle fallback
            if route.get('fallback'):
                fallback_data = {}
                if route['fallback'].get('motd'):
                    fallback_data['motd'] = route['fallback']['motd']
                if route['fallback'].get('versionName') or route['fallback'].get('versionProtocol'):
                    fallback_data['version'] = {
                        'name': route['fallback'].get('versionName', ''),
                        'protocol': route['fallback'].get('versionProtocol', -1)
                    }
                if fallback_data:
                    route_config['fallback'] = fallback_data

            routes.append(route_config)

        config = {
            'config': {
                'proxyProtocol': data.get('proxyProtocol', True),
                'lite': {
                    'enabled': True,
                    'routes': routes
                }
            }
        }

        with open(CONFIG_PATH, 'w') as f:
            yaml.dump(config, f, default_flow_style=False, sort_keys=False, allow_unicode=True)

        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)