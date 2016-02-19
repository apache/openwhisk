echo === Ubuntu Desktop

sudo apt-get update
sudo apt-get install -y python-software-properties
sudo apt-get install -y virtualbox-guest-dkms virtualbox-guest-utils virtualbox-guest-x11
sudo VBoxClient-all
sudo apt-get install -y ubuntu-desktop

echo either reboot to enable the desktop or use startx
