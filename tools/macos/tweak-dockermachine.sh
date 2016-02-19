# Setup docker-machine VM and OSX host.
# Assumes there exists a docker-machine called whisk; to create one:
# > docker-machine create whisk --driver virtualbox

# Set this to the name of the docker-machine VM.
MACHINE_NAME=whisk

# Disable TLS.
docker-machine ssh $MACHINE_NAME "echo DOCKER_TLS=no |sudo tee -a /var/lib/boot2docker/profile > /dev/null"
docker-machine ssh $MACHINE_NAME "echo DOCKER_HOST=\'-H tcp://0.0.0.0:2375\' |sudo tee -a /var/lib/boot2docker/profile > /dev/null"

# Forward standard docker port.
docker-machine ssh $MACHINE_NAME "echo sudo iptables -t nat -A PREROUTING -p tcp --dport 4243 -j REDIRECT --to-port 2375 |sudo tee -a /var/lib/boot2docker/profile > /dev/null"

# Run syslogd.
docker-machine ssh $MACHINE_NAME "echo /sbin/syslogd |sudo tee /var/lib/boot2docker/bootsync.sh > /dev/null"
docker-machine ssh $MACHINE_NAME "sudo chmod +x /var/lib/boot2docker/bootsync.sh"

# Set routes on host.
# If you notice the route forwarding is immediately deleted after this script
# runs (you can check by running 'route monitor') then shut down your networking
# (turn off wifi and disconnet all networking cables), wait a few secs and try
# again; you should see the route stick and now you can reenable networking.
MACHINE_VM_IP=$(docker-machine ip $MACHINE_NAME)
sudo route -n -q delete 172.17.0.0/16
sudo route -n -q add 172.17.0.0/16 $MACHINE_VM_IP
netstat -nr |grep 172\.17

# Env variables to set.
# Note, the user CAN NOT do eval $(docker-machine env whisk) because of a bug in docker-machine that assumes TLS even if not enabled
echo Run the following:
echo "  " export DOCKER_HOST="tcp://$MACHINE_VM_IP:2375"

# Restart docker VM.
docker-machine restart $MACHINE_NAME


