sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install -y ansible=2.0.2.0-1ppa~trusty
sudo pip install docker-py==1.8.1

ansible --version
ansible-playbook --version
