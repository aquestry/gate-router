# Add this to the get_config route where fallback is handled:
if 'fallback' in route:
    fallback = route['fallback']
    route_obj['fallback'] = {
        'motd': fallback.get('motd', ''),
        'versionName': fallback.get('version', {}).get('name', ''),
        'versionProtocol': fallback.get('version', {}).get('protocol', -1),
        'maxPlayers': fallback.get('players', {}).get('max', 0),
        'onlinePlayers': fallback.get('players', {}).get('online', 0)
    }

# Add this to the save_config route where fallback is saved:
if route.get('fallback'):
    fallback_data = {}
    if route['fallback'].get('motd'):
        fallback_data['motd'] = route['fallback']['motd']
    if route['fallback'].get('versionName') or route['fallback'].get('versionProtocol'):
        fallback_data['version'] = {
            'name': route['fallback'].get('versionName', ''),
            'protocol': route['fallback'].get('versionProtocol', -1)
        }
    if route['fallback'].get('maxPlayers') is not None or route['fallback'].get('onlinePlayers') is not None:
        fallback_data['players'] = {
            'max': route['fallback'].get('maxPlayers', 0),
            'online': route['fallback'].get('onlinePlayers', 0)
        }
    if fallback_data:
        route_config['fallback'] = fallback_data