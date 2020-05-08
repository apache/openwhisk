# Custom Openwhisk Deployment

I have made some small changes to the deployment configuration to extend rate limits.
In addition, I needed to be able to update some of the internal configurations like
how long containers are kept warm after an execution.

## Deployment Commands

I use the following commands to deploy the installation:

```sh
# https://github.com/apache/openwhisk/blob/master/tools/ubuntu-setup/README.md
# https://github.com/apache/openwhisk/blob/master/ansible/README.md

# Install git if it is not installed
sudo apt-get install git -y

# Clone openwhisk
git clone https://github.com/apache/openwhisk.git openwhisk

# Change current directory to openwhisk
cd openwhisk

# Install all required software
(cd tools/ubuntu-setup && ./all.sh)

# build docker images
./gradlew distDocker
cd ansible

ansible-playbook -i environments/local setup.yml
ansible-playbook -i environments/local prereq.yml
ansible-playbook -i environments/local couchdb.yml
ansible-playbook -i environments/local initdb.yml
ansible-playbook -i environments/local wipe.yml
# ansible-playbook -i environments/local openwhisk.yml

# deploy openwhisk with ssl certificates
ansible-playbook -i environments/local/ openwhisk.yml -e 'nginx_ssl_path=/home/ubuntu/certs/openwhisk.nima-dev.com' -e 'nginx_ssl_server_cert=fullchain.pem' -e 'nginx_ssl_server_key=privkey.pem' -e 'whisk_api_localhost_name=openwhisk.nima-dev.com'

# installs a catalog of public packages and actions
ansible-playbook -i environments/local postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/local apigateway.yml
ansible-playbook -i environments/local routemgmt.yml
```
