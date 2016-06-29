sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install -y ansible=2.*
sudo pip install docker-py==1.7.2  # >= 1.8.0 requires docker 1.10 on the host

ansible --version
ansible-playbook --version
