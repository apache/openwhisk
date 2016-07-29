sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo pip install markupsafe
sudo pip install ansible==2.0.2.0
sudo pip install docker-py==1.7.2  # >= 1.8.0 requires docker 1.10 on the host

ansible --version
ansible-playbook --version
