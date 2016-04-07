#!/bin/bash

# Internal functions for repeated parts of the configuration

# API port stuff
function apiPort() {
    echo "   location /docs {"
    echo "      proxy_pass http://$CONTROLLER_HOST:$CONTROLLER_PORT;"
    echo "   }"
    echo "   location /api-docs {"
    echo "      proxy_pass http://$CONTROLLER_HOST:$CONTROLLER_PORT;"
    echo "   }"
    echo "   location /api/v1 {"
    echo "      proxy_pass http://$CONTROLLER_HOST:$CONTROLLER_PORT;"
    echo "      proxy_read_timeout 300s;"
    echo "   }"
    echo
# regex does not work yet
    echo "   location /openwhisk-0.1.0.tar.gz {"
    echo "      root /etc/nginx;"
    echo "   }"
    echo
    echo "   location /blackbox-0.1.0.tar.gz {"
    echo "      root /etc/nginx;"
    echo "   }"
    echo
    echo "   location /OpenWhiskIOSStarterApp.zip {"
    echo "      root /etc/nginx;"
    echo "   }"
    echo
}

function sslConfig() {
    echo "   ssl_session_cache    shared:SSL:1m;"
    echo "   ssl_session_timeout  10m;"
    echo "   ssl_certificate      /etc/nginx/whisk-cert.pem;"
    echo "   ssl_certificate_key  /etc/nginx/whisk-key.pem;"
    echo "   ssl_verify_client off;"
    echo "   ssl_protocols        TLSv1 TLSv1.1 TLSv1.2;"
    echo "   ssl_ciphers RC4:HIGH:!aNULL:!MD5;"
    echo "   ssl_prefer_server_ciphers on;"
    echo "   proxy_ssl_session_reuse off;"
    echo
}

echo "events {"
echo "  worker_connections  4096;  ## Default: 1024"
echo "}"
echo
echo "http {"
## temporary hack to allow large uploads.,  need to thread proper
## limit into here
echo "  client_max_body_size 50M;"
echo
echo "  rewrite_log on;"
## change log format to display the upstream information
echo "  log_format combined-upstream '\$remote_addr - \$remote_user [\$time_local]  '"
echo "        '\$request \$status \$body_bytes_sent '"
echo "        '\$http_referer \$http_user_agent  \$upstream_addr';"
echo "  access_log /logs/nginx_access.log combined-upstream;"
echo

echo " server {"
echo "   listen 443 default ssl;"
echo

sslConfig
apiPort "Y"
echo "  }"
echo "}"
