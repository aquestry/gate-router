FROM ghcr.io/minekube/gate/jre:latest

WORKDIR /app

RUN apk add --no-cache python3 py3-pip

COPY requirements.txt /app/
RUN pip3 install --break-system-packages -r requirements.txt

COPY config.yml /app/config.yml
COPY api.py /app/api.py
COPY index.html /app/index.html
COPY entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 25565 8080

ENTRYPOINT ["/entrypoint.sh"]