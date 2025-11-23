#!/bin/sh

echo "Starting API server..."
python3 /app/api.py &

echo "Starting Gate proxy..."
exec gate proxy