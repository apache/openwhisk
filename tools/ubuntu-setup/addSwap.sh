# https://www.digitalocean.com/community/tutorials/how-to-add-swap-on-ubuntu-14-04
sudo dd if=/dev/zero of=/swapfile bs=1M count=4000
ls -lh /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
sudo swapon -s
# for this session
sudo swapon /swapfile
sudo swapon -s
# or add to /etc/fstab
echo "/swapfile   none    swap    sw    0   0" | sudo tee -a /etc/fstab
