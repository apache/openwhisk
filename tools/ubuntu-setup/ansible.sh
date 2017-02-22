sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install python-dev -y
sudo apt-get install libffi-dev -y
sudo pip install markupsafe
sudo pip install ansible==2.1.2.0
sudo pip install docker-py==1.9.0

ansible --version
ansible-playbook --version
