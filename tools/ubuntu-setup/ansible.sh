sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install python-dev -y
sudo apt-get install libffi-dev -y
sudo pip install markupsafe
sudo pip install ansible==2.2.1.0
sudo pip install docker-py==1.10.6

ansible --version
ansible-playbook --version
